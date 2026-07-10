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

    private MarketDataProvider notFound(AtomicInteger calls) {
        return new MarketDataProvider() {
            public String name() { return "x"; }
            public Quote quote(String s) {
                calls.incrementAndGet();
                throw new MarketDataException(MarketDataException.Kind.NOT_FOUND, "no such symbol", null);
            }
            public List<OhlcBar> ohlc(String s, int d) {
                calls.incrementAndGet();
                throw new MarketDataException(MarketDataException.Kind.NOT_FOUND, "no such symbol", null);
            }
        };
    }

    @Test
    void unknownSymbolNegativelyCachedFor60Seconds() {
        // M-C4: an all-providers-NOT_FOUND outcome is cached for ~60s so repeated bad-symbol
        // calls short-circuit instead of re-walking every provider.
        AtomicInteger calls = new AtomicInteger();
        AtomicLong now = new AtomicLong(0L);
        MarketDataService svc = new MarketDataService(List.of(notFound(calls)), 1000, now::get);

        assertThatThrownBy(() -> svc.quote("BOGUS")).isInstanceOf(MarketDataException.class);
        assertThat(calls.get()).isEqualTo(1);

        now.set(30_000L); // still within the 60s negative-cache window
        assertThatThrownBy(() -> svc.quote("BOGUS")).isInstanceOf(MarketDataException.class);
        assertThat(calls.get()).isEqualTo(1); // provider not touched again

        now.set(61_000L); // past the 60s negative-cache window
        assertThatThrownBy(() -> svc.quote("BOGUS")).isInstanceOf(MarketDataException.class);
        assertThat(calls.get()).isEqualTo(2); // re-queried
    }

    @Test
    void unknownOhlcSymbolNegativelyCachedFor60Seconds() {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong now = new AtomicLong(0L);
        MarketDataService svc = new MarketDataService(List.of(notFound(calls)), 1000, now::get);

        assertThatThrownBy(() -> svc.ohlc("BOGUS", 30)).isInstanceOf(MarketDataException.class);
        assertThat(calls.get()).isEqualTo(1);

        now.set(30_000L);
        assertThatThrownBy(() -> svc.ohlc("BOGUS", 30)).isInstanceOf(MarketDataException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void cacheKeyIsCaseNormalized_positiveAndNegative() {
        // Low finding: cache keys must case-normalize so "aapl" and "AAPL" share an entry.
        AtomicInteger ohlcCalls = new AtomicInteger();
        MarketDataService okSvc = new MarketDataService(List.of(counting(ohlcCalls)), 1000, () -> 0L);
        okSvc.ohlc("aapl", 260);
        okSvc.ohlc("AAPL", 260);
        assertThat(ohlcCalls.get()).isEqualTo(1);

        AtomicInteger notFoundCalls = new AtomicInteger();
        AtomicLong now = new AtomicLong(0L);
        MarketDataService badSvc = new MarketDataService(List.of(notFound(notFoundCalls)), 1000, now::get);
        assertThatThrownBy(() -> badSvc.quote("bogus")).isInstanceOf(MarketDataException.class);
        assertThatThrownBy(() -> badSvc.quote("BOGUS")).isInstanceOf(MarketDataException.class);
        assertThat(notFoundCalls.get()).isEqualTo(1); // shared negative-cache entry
    }
}
