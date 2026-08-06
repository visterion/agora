package de.visterion.agora.fetch.edgar;

import java.util.function.LongSupplier;

/**
 * One shared minimum-spacing budget for every sec.gov request an {@link EdgarSearchService}
 * instance makes (BUG-S1a fix round 2).
 *
 * <p><b>Why this is not a method-local variable.</b> The pacing was first written inline in
 * {@code fetchForm4}'s hit loop, which made the headroom under SEC's limit a property of ONE loop
 * rather than of the process. That is the wrong unit: SEC's Internet Security Policy caps a user
 * at 10 requests/second "regardless of the number of machines used to submit requests", so the
 * budget is shared whether or not the code shares it. Concretely, the EFTS page walk that feeds
 * the loop was itself unthrottled — up to 20 pages per search, two searches per Form-4 call — so
 * a paced 9.09 req/s archive stream ran alongside an unpaced EFTS burst and the true combined
 * rate was above both.
 *
 * <p>Same shape as {@link de.visterion.agora.fetch.finnhub.FinnhubRateLimiter}: ONE limiter
 * instance shared by several call paths, so the rate is enforced across the family instead of
 * per-caller. The mechanism differs because the contract differs — Finnhub publishes a
 * calls-per-minute quota and is best served by a refilling token bucket that permits bursts,
 * while SEC publishes an instantaneous requests-per-second ceiling that is best served by a hard
 * minimum spacing between consecutive request starts. A bucket sized 10-per-second would let 10
 * requests leave in the same millisecond, which is exactly the shape SEC rate-limits.
 *
 * <p><b>Spacing, not delay.</b> The wait owed before a request is whatever remains of the
 * {@code minSpacingMs} window since the PREVIOUS request started — not the whole window. A
 * request whose predecessor already took longer than the window waits zero and is then paced by
 * its own duration, which is slower still. The clock is re-read after sleeping rather than
 * projected forward, because a real {@code Thread.sleep} can only overshoot: taking the actual
 * post-sleep instant keeps every gap at or above {@code minSpacingMs}.
 *
 * <p>{@code synchronized} because {@link EdgarSearchService} is a singleton {@code @Component}
 * and Tomcat may call it from several request threads at once; an unsynchronised
 * last-start field would let two threads both read a stale instant and fire together.
 * Contention is bounded by the spacing itself and the critical section contains only the sleep
 * this class exists to perform.
 */
final class EdgarRequestPacer {

    private final long minSpacingMs;
    private final LongSupplier now;
    private final EdgarSearchService.Sleeper sleeper;

    /** Instant the previous request STARTED; {@link Long#MIN_VALUE} until the first one. */
    private long previousRequestStartedAt = Long.MIN_VALUE;

    EdgarRequestPacer(long minSpacingMs, LongSupplier now, EdgarSearchService.Sleeper sleeper) {
        this.minSpacingMs = minSpacingMs;
        this.now = now;
        this.sleeper = sleeper;
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
     * (a {@code RestClient} interceptor / an exception-swallowing per-filing parse). Restores the
     * interrupt flag so the surrounding loop's own checks still see it.
     */
    void acquireUninterruptibly() {
        try {
            acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
