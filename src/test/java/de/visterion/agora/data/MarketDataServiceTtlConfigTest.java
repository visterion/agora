package de.visterion.agora.data;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M-B2: quote and ohlc caches must expire independently — quote TTL 300s, ohlc TTL 120s —
 * so the two positive-result caches are no longer bound to one shared {@code ttlMillis}.
 */
class MarketDataServiceTtlConfigTest {

    private MarketDataProvider counting(AtomicInteger quoteCalls, AtomicInteger ohlcCalls) {
        return new MarketDataProvider() {
            public String name() { return "stub"; }
            public Quote quote(String s) {
                quoteCalls.incrementAndGet();
                return new Quote(s, new BigDecimal("10"), BigDecimal.ZERO, "USD");
            }
            public List<OhlcBar> ohlc(String s, int d) {
                ohlcCalls.incrementAndGet();
                return List.of(new OhlcBar(java.time.LocalDate.parse("2025-01-02"),
                        new BigDecimal("9"), new BigDecimal("11"), new BigDecimal("8"), new BigDecimal("10"), 100L));
            }
        };
    }

    @Test
    void quoteAndOhlcCachesExpireIndependently() {
        AtomicInteger quoteCalls = new AtomicInteger();
        AtomicInteger ohlcCalls = new AtomicInteger();
        AtomicLong now = new AtomicLong(0L);
        MarketDataService svc = new MarketDataService(
                List.of(counting(quoteCalls, ohlcCalls)),
                300_000L, // quote TTL: 300s
                120_000L, // ohlc TTL: 120s
                now::get);

        svc.quote("AAPL");
        svc.ohlc("AAPL", 260);
        assertThat(quoteCalls.get()).isEqualTo(1);
        assertThat(ohlcCalls.get()).isEqualTo(1);

        // t=200s: quote still fresh (TTL 300s), ohlc already expired (TTL 120s)
        now.set(200_000L);
        svc.quote("AAPL");
        svc.ohlc("AAPL", 260);
        assertThat(quoteCalls.get()).isEqualTo(1); // still cached
        assertThat(ohlcCalls.get()).isEqualTo(2);  // re-fetched after 120s

        // t=301s: quote now past its 300s TTL too
        now.set(301_000L);
        svc.quote("AAPL");
        assertThat(quoteCalls.get()).isEqualTo(2); // re-fetched after 300s
    }
}
