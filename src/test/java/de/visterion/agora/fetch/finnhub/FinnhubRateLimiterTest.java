package de.visterion.agora.fetch.finnhub;

import de.visterion.agora.data.MarketDataException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;

class FinnhubRateLimiterTest {

    private final AtomicLong clock = new AtomicLong(0L);

    @Test void allowsUpToTheLimitWithinTheMinute() {
        var limiter = new FinnhubRateLimiter(3, 2_000L, clock::get);
        for (int i = 0; i < 3; i++) assertThat(limiter.tryAcquire()).isTrue();
    }

    @Test void refusesBeyondTheLimit() {
        var limiter = new FinnhubRateLimiter(3, 2_000L, clock::get);
        for (int i = 0; i < 3; i++) limiter.tryAcquire();
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test void refillsAfterTheWindow() {
        var limiter = new FinnhubRateLimiter(3, 2_000L, clock::get);
        for (int i = 0; i < 3; i++) limiter.tryAcquire();
        clock.set(60_001L);
        assertThat(limiter.tryAcquire()).isTrue();
    }

    // Fix round 1, finding 2: a fixed-window counter that only resets when the window rolls over
    // lets callsPerMinute calls through just before the boundary AND callsPerMinute more just
    // after it — 2x the configured rate in a few milliseconds. A continuous-refill bucket must not
    // have this cliff: draining the bucket right before the old window boundary and trying again
    // 1ms later (barely any continuous refill in 1ms) must still be refused.
    @Test void burstAcrossTheOldFixedWindowBoundaryIsStillThrottled() {
        var limiter = new FinnhubRateLimiter(3, 2_000L, clock::get);
        clock.set(59_999L);
        for (int i = 0; i < 3; i++) assertThat(limiter.tryAcquire()).isTrue();
        clock.set(60_000L); // 1ms later: a fixed-window reset would wrongly allow a fresh burst here
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test void tokensRefillProportionallyToElapsedTimeNotInDiscreteWindowJumps() {
        var limiter = new FinnhubRateLimiter(3, 2_000L, clock::get);
        for (int i = 0; i < 3; i++) limiter.tryAcquire(); // drains the bucket at t=0
        clock.set(20_000L); // a third of the window: exactly 1 token should have refilled
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test void failFastModeThrowsImmediatelyWhenExhausted() {
        var limiter = new FinnhubRateLimiter(1, 2_000L, clock::get);
        limiter.tryAcquire();
        assertThatThrownBy(() -> limiter.acquire(FinnhubRateLimiter.Mode.FAIL_FAST))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("rate limit");
    }

    @Test void waitModeGivesUpAfterTheBound() {
        // maxWait is far below the refill window, so the bounded wait must expire, not block.
        var limiter = new FinnhubRateLimiter(1, 50L, clock::get);
        limiter.tryAcquire();
        assertThatThrownBy(() -> limiter.acquire(FinnhubRateLimiter.Mode.WAIT))
                .isInstanceOf(MarketDataException.class);
    }

    @Test void interruptionRestoresTheFlagAndFails() throws Exception {
        var limiter = new FinnhubRateLimiter(1, 60_000L, clock::get);
        limiter.tryAcquire();

        var caught = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        Thread t = new Thread(() -> {
            try { limiter.acquire(FinnhubRateLimiter.Mode.WAIT); }
            catch (Throwable e) { caught.set(e); interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        t.start();
        Thread.sleep(100);
        t.interrupt();
        t.join(5_000);

        assertThat(caught.get()).isInstanceOf(MarketDataException.class);
        assertThat(interrupted).isTrue();
    }

    // ---- Step 1: defensive header parsing (Retry-After seconds / HTTP-date, x-ratelimit-reset
    // epoch seconds, and the configured default when neither is present or parseable). ----

    @Test void retryAfterSecondsWinsOverDefault() {
        var limiter = new FinnhubRateLimiter(1, 60_000L, clock::get, 2_000L);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "5");
        assertThat(limiter.resolveWaitMs(headers)).isEqualTo(5_000L);
    }

    @Test void retryAfterHttpDateIsConvertedToADelta() {
        clock.set(1_000L);
        var limiter = new FinnhubRateLimiter(1, 60_000L, clock::get, 2_000L);
        HttpHeaders headers = new HttpHeaders();
        // 3 seconds after the epoch, clock is at 1000ms -> expected delta ~2000ms.
        headers.set(HttpHeaders.RETRY_AFTER, "Thu, 01 Jan 1970 00:00:03 GMT");
        assertThat(limiter.resolveWaitMs(headers)).isEqualTo(2_000L);
    }

    // Fix round 1, finding 1: x-ratelimit-reset is ambiguous without a captured live 429 —
    // RssNewsProvider's existing precedent for this same header name treats it as a *relative*
    // second count, not an absolute epoch timestamp. A small value must be read as relative
    // seconds, not as "epoch seconds minus now" (which would go deeply negative and silently
    // clamp to zero — a no-op backoff at exactly the moment backoff is needed).
    @Test void rateLimitResetBelowSanityThresholdIsTreatedAsRelativeSeconds() {
        clock.set(1_000L);
        var limiter = new FinnhubRateLimiter(1, 60_000L, clock::get, 2_000L);
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-ratelimit-reset", "5"); // relative seconds -> 5000ms, NOT epoch-minus-now
        assertThat(limiter.resolveWaitMs(headers)).isEqualTo(5_000L);
    }

    @Test void rateLimitResetJustBelowTheSanityThresholdIsStillRelative() {
        var limiter = new FinnhubRateLimiter(1, 60_000L, clock::get, 2_000L);
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-ratelimit-reset", "999999999");
        assertThat(limiter.resolveWaitMs(headers)).isEqualTo(999_999_999L * 1000L);
    }

    @Test void rateLimitResetAtOrAboveTheSanityThresholdIsTreatedAsAbsoluteEpochSeconds() {
        clock.set(1_000L);
        var limiter = new FinnhubRateLimiter(1, 60_000L, clock::get, 2_000L);
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-ratelimit-reset", "1000000000"); // exactly at the threshold -> absolute
        assertThat(limiter.resolveWaitMs(headers)).isEqualTo(1_000_000_000L * 1000L - 1_000L);
    }

    @Test void neitherHeaderPresentFallsBackToTheConfiguredDefault() {
        var limiter = new FinnhubRateLimiter(1, 60_000L, clock::get, 2_000L);
        assertThat(limiter.resolveWaitMs(new HttpHeaders())).isEqualTo(2_000L);
    }

    @Test void unparseableHeadersFallBackToTheConfiguredDefault() {
        var limiter = new FinnhubRateLimiter(1, 60_000L, clock::get, 2_000L);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "not-a-number-or-date");
        headers.set("x-ratelimit-reset", "also-not-a-number");
        assertThat(limiter.resolveWaitMs(headers)).isEqualTo(2_000L);
    }
}
