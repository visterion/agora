package de.visterion.agora.fetch.finnhub;

import de.visterion.agora.data.MarketDataException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.function.LongSupplier;

/**
 * Shared Finnhub call-rate policy (M-D23): every Finnhub caller gets its OWN {@link
 * de.visterion.agora.data.DataHttp}-built {@code RestClient} (so each keeps its own deliberate
 * timeout — see {@link de.visterion.agora.data.FinnhubMarketDataProvider}'s 4s fail-fast contract
 * vs {@link FinnhubEarningsProvider}'s bounded wait), but they all share ONE instance of this
 * limiter as a request interceptor, so the token bucket below throttles the combined call rate
 * across the whole Finnhub family instead of per-caller.
 *
 * <p>Registered ahead of {@link de.visterion.agora.observability.ProviderCallLogger} on every
 * caller's builder (see {@code DataHttp.clientBuilder(long, ClientHttpRequestInterceptor)}), so a
 * bounded wait here is never billed to the logged {@code provider_call} duration.
 *
 * <p>Two failure modes on exhaustion, per caller:
 * <ul>
 *   <li>{@link Mode#FAIL_FAST} — throws immediately (the quote path has an alternative provider
 *       to fall through to).</li>
 *   <li>{@link Mode#WAIT} — blocks (real wall-clock, bounded by {@code maxWaitMs}) until a slot
 *       frees up or the bound is exceeded (fetch/earnings/news paths have no alternative).</li>
 * </ul>
 */
@Component
public class FinnhubRateLimiter implements ClientHttpRequestInterceptor {

    public enum Mode { WAIT, FAIL_FAST }

    private static final long WINDOW_MS = 60_000L;
    private static final long POLL_INTERVAL_MS = 25L;

    private final int callsPerMinute;
    private final long maxWaitMs;
    private final LongSupplier now;
    private final long defaultRetryAfterMs;

    private long windowStart = 0L;
    private int count = 0;
    private long blockedUntilMs = 0L;

    /** Spring-bound constructor: real wall clock, configured limit/bound/default. */
    @Autowired
    public FinnhubRateLimiter(
            @Value("${agora.data.finnhub.calls-per-minute:60}") int callsPerMinute,
            @Value("${agora.data.finnhub.max-wait-ms:3000}") long maxWaitMs,
            @Value("${agora.data.finnhub.default-retry-after-ms:2000}") long defaultRetryAfterMs) {
        this(callsPerMinute, maxWaitMs, System::currentTimeMillis, defaultRetryAfterMs);
    }

    /** Test constructor matching the plan's interface spec: injected clock, default fallback wait. */
    public FinnhubRateLimiter(int callsPerMinute, long maxWaitMs, LongSupplier now) {
        this(callsPerMinute, maxWaitMs, now, 2_000L);
    }

    FinnhubRateLimiter(int callsPerMinute, long maxWaitMs, LongSupplier now, long defaultRetryAfterMs) {
        this.callsPerMinute = callsPerMinute;
        this.maxWaitMs = maxWaitMs;
        this.now = now;
        this.defaultRetryAfterMs = defaultRetryAfterMs;
    }

    /** Non-blocking: true if a slot was available and consumed, false if the caller is exhausted. */
    public synchronized boolean tryAcquire() {
        long ts = now.getAsLong();
        if (ts < blockedUntilMs) return false;
        if (ts - windowStart >= WINDOW_MS) {
            windowStart = ts;
            count = 0;
        }
        if (count < callsPerMinute) {
            count++;
            return true;
        }
        return false;
    }

    /**
     * Consumes a slot, per {@code mode}. {@link Mode#FAIL_FAST} throws immediately when exhausted.
     * {@link Mode#WAIT} polls (real wall-clock, never the injected logical clock) until a slot
     * frees up or {@code maxWaitMs} elapses. On interruption during the wait, restores the
     * interrupt flag and throws — never swallows it.
     */
    public void acquire(Mode mode) {
        if (tryAcquire()) return;
        if (mode == Mode.FAIL_FAST) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "finnhub rate limit exceeded", null);
        }
        long deadlineNanos = System.nanoTime() + maxWaitMs * 1_000_000L;
        while (true) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                        "finnhub rate limit wait exceeded " + maxWaitMs + "ms", null);
            }
            long sleepMs = Math.max(1L, Math.min(POLL_INTERVAL_MS, remainingNanos / 1_000_000L));
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                        "finnhub rate limit wait interrupted", e);
            }
            if (tryAcquire()) return;
        }
    }

    /** Records a cooldown derived from the 429 response headers, blocking further {@link #tryAcquire()}
     *  calls until it elapses. */
    private synchronized void recordCooldown(HttpHeaders headers) {
        long waitMs = resolveWaitMs(headers);
        blockedUntilMs = Math.max(blockedUntilMs, now.getAsLong() + waitMs);
    }

    /**
     * Step 1: defensive header parsing. Finnhub's exact rate-limit header is unverified, so both
     * variants this codebase has already seen are supported, with a configured default when
     * neither is present or parseable:
     * <ul>
     *   <li>{@code Retry-After} — seconds, or an HTTP date.</li>
     *   <li>{@code x-ratelimit-reset} — epoch seconds (the form {@code RssNewsProvider} handles).</li>
     * </ul>
     */
    long resolveWaitMs(HttpHeaders headers) {
        long nowMs = now.getAsLong();
        Long retryAfter = parseRetryAfterMs(headers.getFirst(HttpHeaders.RETRY_AFTER), nowMs);
        if (retryAfter != null) return retryAfter;
        Long reset = parseEpochSecondsToDeltaMs(headers.getFirst("x-ratelimit-reset"), nowMs);
        if (reset != null) return reset;
        return defaultRetryAfterMs;
    }

    private static Long parseRetryAfterMs(String value, long nowMs) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim();
        try {
            return Long.parseLong(v) * 1000L;
        } catch (NumberFormatException ignored) {
            // fall through to HTTP-date parsing
        }
        try {
            long epochMs = java.time.ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli();
            return Math.max(0L, epochMs - nowMs);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Long parseEpochSecondsToDeltaMs(String value, long nowMs) {
        if (value == null || value.isBlank()) return null;
        try {
            long epochMs = Long.parseLong(value.trim()) * 1000L;
            return Math.max(0L, epochMs - nowMs);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Default interceptor behaviour: {@link Mode#WAIT} (used directly by callers with no
     *  alternative provider to fall back to, e.g. {@link FinnhubClient}). */
    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        return interceptWithMode(Mode.WAIT, request, body, execution);
    }

    /** Returns an interceptor view sharing this limiter's token-bucket state, for callers that
     *  need {@link Mode#FAIL_FAST} instead of the default {@link Mode#WAIT} (the quote path). */
    public ClientHttpRequestInterceptor withMode(Mode mode) {
        return (request, body, execution) -> interceptWithMode(mode, request, body, execution);
    }

    private ClientHttpResponse interceptWithMode(Mode mode, HttpRequest request, byte[] body,
                                                  ClientHttpRequestExecution execution) throws IOException {
        acquire(mode);
        ClientHttpResponse response = execution.execute(request, body);
        if (response.getStatusCode().value() == 429) {
            recordCooldown(response.getHeaders());
        }
        return response;
    }
}
