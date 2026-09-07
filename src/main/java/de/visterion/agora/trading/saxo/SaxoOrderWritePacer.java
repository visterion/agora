package de.visterion.agora.trading.saxo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.lang.NonNull;

import java.io.IOException;
import java.net.URI;
import java.util.function.LongSupplier;

/**
 * Per-connection spacing for Saxo ORDER WRITES. Saxo allows one order operation per second per
 * session; every writer (the nightly stop ratchet, a flatten, this provider's own far-stop
 * fallback and rollback interleave) funnels through one {@code RestClient} per connection, so one
 * interceptor on that client is the only place that sees them all.
 *
 * <p><b>It waits; it never throws.</b> There is no wait cap and no {@code NOT_READY}: a throw
 * would turn a client-side queue into a broker failure, and the naked-entry fail-safe would
 * itself be several paced writes — a throwing pacer would block the rescue with the same
 * condition that triggered it. The total wait is bounded by construction instead: one interval
 * plus one clamped 429 block.
 *
 * <p><b>Assumption:</b> one writer per connection at a time. The pacer serializes RELEASES; two
 * concurrent callers would afterwards race through later layers. If that ever stops being true,
 * the spacing has to move from release to wire send.
 *
 * <p><b>Header discovery.</b> Every paced response's {@code X-RateLimit-*} headers are logged at
 * INFO (see {@link #headerLogLine}) — on a 200 as much as on a 429. This is the only place a
 * broker RESPONSE header is visible at all in this codebase, so it is how the real header names
 * are discovered after deploy. Until those names are read off a real run, the 429 block runs on
 * the configured default and the clamp bounds any header this class has never seen.
 *
 * <p>{@code FinnhubRateLimiter} is the template (injected clock, every branch clamped, interrupt
 * flag restored). The 429 header precedence is deliberately different from it and reads relative
 * seconds only — see {@link #recordBlock}.
 */
public final class SaxoOrderWritePacer implements ClientHttpRequestInterceptor {

    /** Sleep seam: production sleeps the real wall clock, tests advance the injected clock. */
    @FunctionalInterface
    public interface Sleeper {
        void sleepMs(long millis) throws InterruptedException;
    }

    private static final Logger log = LoggerFactory.getLogger(SaxoOrderWritePacer.class);

    /**
     * Matched with {@code contains}, not {@code startsWith}: the RestClient carries the base URL,
     * so the interceptor sees an absolute URI — SIM {@code .../sim/openapi/trade/v2/orders}, LIVE
     * {@code .../openapi/trade/v2/orders}, DELETE {@code .../trade/v2/orders/{id}}.
     */
    static final String ORDER_WRITE_PATH = "/trade/v2/orders";

    /** The ONE rate-limit dimension the order limit lives on. {@code X-RateLimit-AppDay-Reset}
     *  runs to 86 400 s and says nothing about this request's cooldown — it is never read
     *  for the block, only logged for discovery. */
    static final String SESSION_ORDERS_RESET = "X-RateLimit-SessionOrders-Reset";

    /**
     * Only response headers whose name starts with this (case-insensitively) are logged. Nothing
     * else from the response ever reaches the log through this path — no body, no auth echo.
     */
    private static final String RATE_LIMIT_PREFIX = "X-RateLimit-";

    private final long minIntervalMs;
    private final long maxBlockMs;
    private final long defaultRetryAfterMs;
    private final LongSupplier now;
    private final Sleeper sleeper;

    /** Earliest instant at which the NEXT paced request may be released. */
    private long nextReleaseMs = Long.MIN_VALUE;
    /** Extra block imposed by a 429, clamped to {@link #maxBlockMs}. */
    private long blockedUntilMs = 0L;

    /** Production constructor: real wall clock, real sleeps. */
    public SaxoOrderWritePacer(long minIntervalMs, long maxBlockMs, long defaultRetryAfterMs) {
        this(minIntervalMs, maxBlockMs, defaultRetryAfterMs, System::currentTimeMillis, Thread::sleep);
    }

    /** Test constructor: injected clock and sleep seam, so no test ever really sleeps. */
    SaxoOrderWritePacer(long minIntervalMs, long maxBlockMs, long defaultRetryAfterMs,
                        LongSupplier now, Sleeper sleeper) {
        this.minIntervalMs = minIntervalMs;
        this.maxBlockMs = maxBlockMs;
        this.defaultRetryAfterMs = defaultRetryAfterMs;
        this.now = now;
        this.sleeper = sleeper;
    }

    @Override
    @NonNull
    public ClientHttpResponse intercept(@NonNull HttpRequest request, @NonNull byte[] body,
                                        @NonNull ClientHttpRequestExecution execution)
            throws IOException {
        // minIntervalMs = 0 is the documented kill switch: full pass-through, no spacing AND no
        // 429 block, so an operator can disable pacing with one env var.
        if (minIntervalMs <= 0 || !isOrderWrite(request)) return execution.execute(request, body);
        // The wait outcome is computed inside the synchronized awaitSlot call but logged only
        // AFTER execution.execute() below, never in between: logging is slow enough relative to
        // the millisecond-granularity spacing this class enforces that any log call sitting
        // between "release decided" and "request actually sent" would jitter the send instant
        // itself — exactly the kind of silent under-pacing this class must not introduce. The
        // same reasoning is why nothing here logs while holding the pacer's lock (see awaitSlot).
        WaitOutcome wait = awaitSlot(request);
        ClientHttpResponse response = execution.execute(request, body);
        logWaitOutcome(request, wait);
        int status = response.getStatusCode().value();
        // Header discovery: this interceptor is the ONLY place a broker response header is
        // visible. Logged on every paced request, 200 as well as 429, so the names are on
        // record before the first 429 ever arrives.
        log.info("{}", headerLogLine(status, request, response.getHeaders()));
        if (status == 429) {
            BlockOutcome block = recordBlock(response.getHeaders());
            log.info("saxo write pacer: 429 blocks order writes for {} ms (header={})",
                    block.waitMs(), block.source());
        }
        return response;
    }

    /**
     * Formats the discovery line: {@code saxo write pacer: response {status} {method} {path}
     * headers {name=value,...}}, carrying every {@code X-RateLimit-*} response header and nothing
     * else. Package-private so a test can assert the exact shape without a log appender.
     */
    static String headerLogLine(int status, HttpRequest request, HttpHeaders headers) {
        StringBuilder rateLimit = new StringBuilder();
        headers.forEach((name, values) -> {
            if (!name.regionMatches(true, 0, RATE_LIMIT_PREFIX, 0, RATE_LIMIT_PREFIX.length())) return;
            for (String value : values) {
                if (!rateLimit.isEmpty()) rateLimit.append(',');
                rateLimit.append(name).append('=').append(value);
            }
        });
        String path = request.getURI() == null ? "-" : request.getURI().getPath();
        return "saxo write pacer: response " + status + " " + request.getMethod() + " " + path
                + " headers {" + rateLimit + "}";
    }

    static boolean isOrderWrite(HttpRequest request) {
        HttpMethod method = request.getMethod();
        if (!HttpMethod.POST.equals(method)
                && !HttpMethod.PATCH.equals(method)
                && !HttpMethod.DELETE.equals(method)) {
            return false;
        }
        URI uri = request.getURI();
        String path = uri == null ? null : uri.getPath();
        return path != null && path.contains(ORDER_WRITE_PATH);
    }

    /** Outcome of one {@link #awaitSlot} call, carried out of the synchronized section so the
     *  caller can log it without holding the pacer's lock. */
    private record WaitOutcome(long waitedMs, boolean interrupted) {}

    /** Outcome of one {@link #recordBlock} call, carried out for the same reason. */
    private record BlockOutcome(long waitMs, String source) {}

    /** Blocks until this request's slot. Never throws — an interrupt restores the flag and
     *  releases the request rather than dropping an order write on the floor. Does no logging
     *  itself: see the caller for why. */
    private synchronized WaitOutcome awaitSlot(HttpRequest request) {
        long arrivedMs = now.getAsLong();
        while (true) {
            long ts = now.getAsLong();
            long remainingMs = Math.max(nextReleaseMs, blockedUntilMs) - ts;
            if (remainingMs <= 0) {
                nextReleaseMs = ts + minIntervalMs;
                return new WaitOutcome(ts - arrivedMs, false);
            }
            try {
                sleeper.sleepMs(remainingMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                long releasedAt = now.getAsLong();
                nextReleaseMs = releasedAt + minIntervalMs;
                return new WaitOutcome(releasedAt - arrivedMs, true);
            }
        }
    }

    private void logWaitOutcome(HttpRequest request, WaitOutcome wait) {
        String path = request.getURI() == null ? "-" : request.getURI().getPath();
        if (wait.interrupted()) {
            log.info("saxo write pacer: wait interrupted after {} ms, sending {} {} anyway",
                    wait.waitedMs(), request.getMethod(), path);
        } else if (wait.waitedMs() > 0) {
            // Without this line, "zero 429 tonight" is indistinguishable from "pacer inert" — a
            // wrong path predicate or a kill switch left on would look identical to success.
            log.info("saxo write pacer: waited {} ms for {} {}", wait.waitedMs(), request.getMethod(), path);
        }
    }

    /**
     * Translates a 429 into a clamped block. Precedence: {@link #SESSION_ORDERS_RESET} (relative
     * seconds, the dimension the order limit lives on) → {@code Retry-After} (relative seconds) →
     * the configured default. Every branch is clamped to {@code maxBlockMs}. The pacer issues no
     * retry of its own. Does no logging itself — without a line naming the resolved wait and its
     * source ({@code session-orders-reset}, {@code retry-after}, or {@code default}) a 429 that
     * falls through to the default would be invisible, and the post-deploy header-name discovery
     * (see the class javadoc) could not tell which branch prod actually exercised; the caller logs
     * the returned {@link BlockOutcome} outside this method's lock for the same reason
     * {@link #awaitSlot} does not log internally either.
     */
    private synchronized BlockOutcome recordBlock(HttpHeaders headers) {
        String source;
        long waitMs;
        Long sessionReset = parseSecondsToMs(headers.getFirst(SESSION_ORDERS_RESET));
        if (sessionReset != null) {
            source = "session-orders-reset";
            waitMs = clamp(sessionReset);
        } else {
            Long retryAfter = parseSecondsToMs(headers.getFirst(HttpHeaders.RETRY_AFTER));
            if (retryAfter != null) {
                source = "retry-after";
                waitMs = clamp(retryAfter);
            } else {
                source = "default";
                waitMs = clamp(defaultRetryAfterMs);
            }
        }
        blockedUntilMs = Math.max(blockedUntilMs, now.getAsLong() + waitMs);
        return new BlockOutcome(waitMs, source);
    }

    private long clamp(long waitMs) {
        return Math.max(0L, Math.min(waitMs, maxBlockMs));
    }

    private static Long parseSecondsToMs(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds < 0 ? null : seconds * 1000L;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Package-private observer so tests can assert the block directly. */
    synchronized long blockedUntilMs() {
        return blockedUntilMs;
    }
}
