package de.visterion.agora.data;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;

class MarketDataServiceCachingTest {

    private MarketDataProvider counting(AtomicInteger ohlcCalls) {
        return new MarketDataProvider() {
            public String name() { return "stub"; }
            public Quote quote(String s) { return new Quote(s, new BigDecimal("10"), BigDecimal.ZERO, "USD"); }
            public List<OhlcBar> ohlc(String s, int d) {
                ohlcCalls.incrementAndGet();
                return List.of(new OhlcBar(java.time.LocalDate.parse("2025-01-02"),
                        new BigDecimal("9"), new BigDecimal("11"), new BigDecimal("8"), new BigDecimal("10"), 100L));
            }
        };
    }

    @Test
    void secondOhlcWithinTtlNotRefetched() {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong now = new AtomicLong(0L);
        MarketDataService svc = new MarketDataService(List.of(counting(calls)), 1000, now::get);
        svc.ohlc("AAPL", 260);
        svc.ohlc("AAPL", 260);
        assertThat(calls.get()).isEqualTo(1);
        now.set(1001);
        svc.ohlc("AAPL", 260);
        assertThat(calls.get()).isEqualTo(2); // expired → refetch
    }

    @Test
    void failureNotCached() {
        AtomicLong now = new AtomicLong(0L);
        AtomicInteger calls = new AtomicInteger();
        MarketDataProvider failing = new MarketDataProvider() {
            public String name() { return "x"; }
            public Quote quote(String s) { throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "down", null); }
            public List<OhlcBar> ohlc(String s, int d) { calls.incrementAndGet(); throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "down", null); }
        };
        MarketDataService svc = new MarketDataService(List.of(failing), 1000, now::get);
        assertThatThrownBy(() -> svc.ohlc("AAPL", 260)).isInstanceOf(MarketDataException.class);
        assertThatThrownBy(() -> svc.ohlc("AAPL", 260)).isInstanceOf(MarketDataException.class);
        assertThat(calls.get()).isEqualTo(2); // not cached → retried
    }
}
