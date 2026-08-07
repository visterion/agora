package de.visterion.agora.fetch.edgar;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;
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
 * <p><b>Contention is real, and the queue is FAIR — first come, first served.</b> Every EDGAR path
 * now waits on this one lock, and the realistic worst case is a market-wide
 * {@code get_form4_transactions} sweep: ~272 archive GETs plus its EFTS walk, i.e. up to the full
 * 30 s Form-4 deadline of back-to-back 110 ms slices, with the sweep's thread re-entering the
 * moment each slice ends. Round 3 used {@code synchronized} and slept inside the monitor, and
 * Java's intrinsic monitor is NOT fair: the re-entering sweep could win the race against an
 * already-waiting {@code get_filing_text} or {@code get_filings} caller over and over, so that
 * caller could be delayed past a 25 s consumer timeout and, pathologically, starved for the
 * sweep's whole duration. The wait was not bounded by anything the waiter could see.
 *
 * <p><b>Why a fair lock and NOT a bounded wait that fails with "busy".</b> Round 3's own note
 * suggested "a fair queue plus a bounded wait that fails busy". Fairness alone turns out to be
 * enough, so the bounded wait is deliberately not built. With {@code ReentrantLock(true)} the lock
 * is granted in arrival order and the sweep's re-entry goes to the BACK of the queue, so a waiter
 * is overtaken at most once — by whoever already holds the lock. Its wait is therefore bounded by
 * the QUEUE, not by the sweep: at most {@code (callers ahead of it + 1) × 110 ms}. The number of
 * threads that can be inside an EDGAR call at once is small and already bounded elsewhere —
 * {@code get_filing_text} admits at most {@link EdgarSearchService#DEFAULT_MAX_CONCURRENT_FILING_FETCHES}
 * (8) concurrently and the remaining paths are one request per in-flight tool call — so a realistic
 * ~10 waiters is a worst case of ~1.1 s, and even a wildly pessimistic 50 waiters is ~5.5 s. Both
 * are far under the 25 s consumer timeout; the 30 s sweep deadline never enters the arithmetic
 * again, because no waiter queues behind more than one of the sweep's 110 ms slices. A bounded
 * wait would buy nothing against that and would invent a failure mode that does not exist today:
 * every EDGAR caller would have to handle a new "busy" error, and this project's recurring bug
 * shape is precisely a degradation that reaches the consumer looking like a normal empty result.
 * A wait that is provably ~1 s is better engineering than an error nobody can be trusted to report.
 * A caller is therefore never refused here — it waits its turn and gets a real answer.
 *
 * <p>Waiting at all is still the honest price of a correct global rate: SEC's 10 req/s is genuinely
 * shared, so a concurrent caller must wait somewhere, and waiting here is strictly better than an
 * IP-level 403 that costs every hunter for hours. Note the lock is fair but the sleep still happens
 * while holding it — that is what makes the spacing a real serialisation point, and fairness is
 * what makes the resulting wait bounded.
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

    /**
     * FAIR by construction — the {@code true} is the whole fix, see "Contention is real" above.
     * An unfair lock (or the {@code synchronized} this replaced) lets the thread that just released
     * barge back in ahead of a caller that has been waiting since before it, which is unbounded
     * starvation; a fair lock hands the monitor over in arrival order, which caps a waiter's delay
     * at one 110 ms slice per caller ahead of it. Do not "optimise" this to {@code new
     * ReentrantLock()} — the throughput difference is invisible next to a 110 ms sleep, and the
     * fairness is the entire reason the class no longer needs a bounded wait.
     */
    private final ReentrantLock gate = new ReentrantLock(true);

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
     * <p>Never fails with a "busy" error and never gives up: {@link #gate} is fair, so the wait is
     * bounded by the queue ahead of the caller (~1 s realistically) rather than by the sweep in
     * front of it. See the class javadoc for why that beats a bounded wait.
     *
     * <p>Interruption is propagated, never swallowed: the caller decides whether a half-finished
     * multi-request operation is a truncation ({@code fetchForm4}) or a failure.
     * {@code lockInterruptibly} rather than {@code lock}, so a caller interrupted while QUEUED
     * reacts as promptly as one interrupted while sleeping — {@code lock()} would swallow the
     * interrupt for the length of the queue.
     */
    long acquire() throws InterruptedException {
        gate.lockInterruptibly();
        try {
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
        } finally {
            gate.unlock();
        }
    }

    /** Test seam: the fairness property itself, pinned where a refactor would silently drop it. */
    boolean queueIsFair() {
        return gate.isFair();
    }

    /** Test seam: how many callers are currently queued behind the holder. */
    int queuedCallers() {
        return gate.getQueueLength();
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
