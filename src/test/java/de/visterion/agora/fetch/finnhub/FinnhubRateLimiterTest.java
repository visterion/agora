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

    @Test void rateLimitResetEpochSecondsIsUsedWhenRetryAfterIsAbsent() {
        clock.set(1_000L);
        var limiter = new FinnhubRateLimiter(1, 60_000L, clock::get, 2_000L);
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-ratelimit-reset", "4"); // epoch seconds -> 4000ms, delta from 1000ms = 3000ms
        assertThat(limiter.resolveWaitMs(headers)).isEqualTo(3_000L);
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
