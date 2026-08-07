package de.visterion.agora.fetch.edgar;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.function.LongSupplier;

/**
 * ONE minimum-spacing budget for every sec.gov request this process makes — the EFTS page walk,
 * the Form-4 archive GETs, {@code get_filing_text}'s archive fetches, {@link EdgarService}'s
 * data.sec.gov calls and {@link EdgarCikResolver}'s ticker-file fetch (BUG-S1a fix round 3).
 *
 * <p><b>Why this is neither a method-local variable nor a per-instance field.</b> The pacing was
 * first written inline in {@code fetchForm4}'s hit loop, which made the headroom under SEC's limit
 * a property of ONE loop; round 2 lifted it to one field per {@link EdgarSearchService} instance,
 * which made it a property of ONE class. Both are the wrong unit. SEC's Internet Security Policy
 * caps a user at "no more than 10 requests per second, regardless of the number of machines used
 * to submit requests", and "Current max request rate: 10 requests/second" (Accessing EDGAR Data);
 * both pages re-read on 2026-08-07. The budget is therefore shared whether or not the code shares
 * it, and four unpaced EDGAR clients running beside a paced one exceed the ceiling while each of
 * them looks compliant on its own.
 *
 * <p>Round 2's own report named the remaining three paths as deliberately outside the budget with
 * the note that it "does not bite today because the only consumer serialises its tool calls, but
 * that is a property of the caller, not a guarantee". That property has since weakened twice: a
 * consumer now issues four Form-4 tool calls per run instead of one, and a single market-wide run
 * was measured at <b>1,095 archive GETs plus 40 EFTS calls in ~150 s</b> (1,143 requests, all HTTP
 * 200) — i.e. ~7.6 req/s of the 10 already spent by one code path, leaving nothing for a
 * concurrent {@code get_filing_text} or {@code get_company_facts} to spend safely.
 *
 * <p><b>How it is shared.</b> Same shape as
 * {@link de.visterion.agora.fetch.finnhub.FinnhubRateLimiter}: a singleton {@code @Component}
 * injected into every caller of the family, applied as a {@link ClientHttpRequestInterceptor} on
 * each caller's own {@code RestClient} (each keeps its own base URL, User-Agent and timeout).
 * Pacing at the HTTP layer rather than at each call site is deliberate — it covers a request the
 * moment the client is built, so a call site added later cannot forget to pace and quietly reopen
 * this same bug. {@link #install(RestClient)} puts the interceptor AHEAD of
 * {@link de.visterion.agora.observability.ProviderCallLogger}, for the reason
 * {@code FinnhubRateLimiter} is registered first too: a spacing wait must never be billed to the
 * provider's measured latency.
 *
 * <p>The mechanism differs from Finnhub's because the contract differs — Finnhub publishes a
 * calls-per-minute quota and is best served by a refilling token bucket that permits bursts, while
 * SEC publishes an instantaneous requests-per-second ceiling that is best served by a hard minimum
 * spacing between consecutive request starts. A bucket sized 10-per-second would let 10 requests
 * leave in the same millisecond, which is exactly the shape SEC rate-limits.
 *
 * <p><b>Spacing, not delay.</b> The wait owed before a request is whatever remains of the
 * {@link #MIN_SPACING_MS} window since the PREVIOUS request started — not the whole window. A
 * request whose predecessor already took longer than the window waits zero and is then paced by
 * its own duration, which is slower still. The clock is re-read after sleeping rather than
 * projected forward, because a real {@code Thread.sleep} can only overshoot: taking the actual
 * post-sleep instant keeps every gap at or above {@code MIN_SPACING_MS}.
 *
 * <p><b>Contention is now real, and it is a known cost.</b> {@link #acquire()} is
 * {@code synchronized} and sleeps INSIDE the monitor, which was harmless while one class held its
 * own pacer and drove it from one thread. Every EDGAR path now queues on this one lock, and the
 * realistic worst case is a market-wide {@code get_form4_transactions} sweep: ~272 archive GETs
 * plus its EFTS walk, i.e. up to the full 30 s Form-4 deadline of back-to-back 110 ms slices. A
 * {@code get_filing_text} or {@code get_filings} call arriving during that sweep is queued behind
 * it, and Java's intrinsic monitor is NOT fair — the sweep's thread re-enters the monitor
 * immediately after each sleep and can win the race repeatedly, so a single unlucky caller can be
 * delayed well past a 25 s consumer timeout and, in the pathological case, starved for the
 * duration of the sweep. That is the honest price of a correct global rate: SEC's 10 req/s is
 * genuinely shared, so a concurrent caller must wait somewhere, and waiting here is strictly
 * better than an IP-level 403 that costs every hunter for hours. If it starts to bite, the fix is
 * a FAIR queue plus a bounded wait that fails with a "busy" error (the shape
 * {@code filing_fetch_busy} already uses), not a second limiter.
 */
@Component
public class EdgarRequestPacer implements ClientHttpRequestInterceptor {

    /**
     * Minimum spacing between the STARTS of two consecutive sec.gov requests, i.e. 9.09 req/s.
     *
     * <p>Lives here rather than in {@link EdgarSearchService} (where it was {@code THROTTLE_MS})
     * because a shared budget implies a shared constant: with four clients drawing on one pacer,
     * a spacing owned by one of them is a claim that class makes about the whole process.
     *
     * <p>SEC publishes a hard ceiling of 10 requests/second — "Current max request rate: 10
     * requests/second" (Accessing EDGAR Data), "our current maximum access rate is 10 requests per
     * second" (webmaster FAQ), and the Internet Security Policy adds that the limit counts
     * "regardless of the number of machines used to submit requests" and that exceeding it gets
     * the IP rate-limited. Re-verified against sec.gov on 2026-08-07. 110 ms leaves ~9 % headroom
     * under that ceiling.
     *
     * <p>This is enforced as a rate limit, not as a fixed delay: see "Spacing, not delay" above.
     * Do not lower this constant to buy speed — the headroom is the whole margin against a 403 IP
     * block.
     */
    public static final long MIN_SPACING_MS = 110;

    private static final EdgarSearchService.Sleeper REAL_SLEEPER = Thread::sleep;

    private final long minSpacingMs;
    private final LongSupplier now;
    private final EdgarSearchService.Sleeper sleeper;

    /** Instant the previous request STARTED; {@link Long#MIN_VALUE} until the first one. */
    private long previousRequestStartedAt = Long.MIN_VALUE;

    /** The bean: real clock, real sleep, SEC's published spacing. */
    @Autowired
    public EdgarRequestPacer() {
        this(MIN_SPACING_MS, System::currentTimeMillis, REAL_SLEEPER);
    }

    EdgarRequestPacer(long minSpacingMs, LongSupplier now, EdgarSearchService.Sleeper sleeper) {
        this.minSpacingMs = minSpacingMs;
        this.now = now;
        this.sleeper = sleeper;
    }

    /**
     * A pacer that never waits, for tests whose subject is not the rate contract.
     *
     * <p>It exists so the "every EDGAR client installs a pacer" invariant has no exception to
     * branch on: a test constructor that took no pacer would be a second, unpaced code path, and
     * an unpaced code path is precisely the bug this class was widened to fix. Zero spacing keeps
     * the wiring identical and only removes the sleep.
     */
    static EdgarRequestPacer unpaced() {
        return new EdgarRequestPacer(0L, System::currentTimeMillis, ms -> { });
    }

    /**
     * Returns {@code client} with this pacer installed as its FIRST interceptor.
     *
     * <p>First, not appended: {@link de.visterion.agora.observability.ProviderCallLogger} is
     * already on every {@code DataHttp}-built client, and an interceptor registered after it would
     * bill the spacing wait to the logged {@code provider_call} duration — up to 110 ms of
     * invented EDGAR latency per request, systematically. Same ordering rule, same reason, as
     * {@code DataHttp.clientBuilder(long, ClientHttpRequestInterceptor)} applies to the Finnhub
     * limiter.
     */
    public RestClient install(RestClient client) {
        return client.mutate().requestInterceptors(interceptors -> interceptors.add(0, this)).build();
    }

    /**
     * Blocks until the caller may start its request, then records that start. Returns the instant
     * the request is cleared to begin.
     *
     * <p>Interruption is propagated, never swallowed: the caller decides whether a half-finished
     * multi-request operation is a truncation ({@code fetchForm4}) or a failure.
     */
    synchronized long acquire() throws InterruptedException {
        long nowMs = now.getAsLong();
        if (previousRequestStartedAt != Long.MIN_VALUE) {
            long waitMs = minSpacingMs - (nowMs - previousRequestStartedAt);
            if (waitMs > 0) {
                sleeper.sleep(waitMs);
                nowMs = now.getAsLong();
            }
        }
        previousRequestStartedAt = nowMs;
        return nowMs;
    }

    /**
     * As {@link #acquire()} but for a caller that cannot propagate an {@link InterruptedException}
     * — which is every caller now that the pacing runs inside an interceptor. Restores the
     * interrupt flag so the surrounding loop's own checks still see it ({@code fetchForm4} marks
     * such a run truncated rather than sprinting through the rest of its hits).
     */
    void acquireUninterruptibly() {
        try {
            acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Pays the spacing owed, then lets the request go. A request the client never sends (a hit
     * with no URL, a cache hit) never reaches an interceptor and therefore never spends a slot.
     */
    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        acquireUninterruptibly();
        return execution.execute(request, body);
    }
}
