package de.visterion.agora.data;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class MarketDataServiceTest {

    private MarketDataProvider failing(String name) {
        return new MarketDataProvider() {
            public String name() { return name; }
            public Quote quote(String symbol) { throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "down", null); }
            public List<OhlcBar> ohlc(String symbol, int days) { throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "down", null); }
        };
    }

    private MarketDataProvider ok(String name) {
        return new MarketDataProvider() {
            public String name() { return name; }
            public Quote quote(String symbol) { return new Quote(symbol, new BigDecimal("10.00"), BigDecimal.ZERO, "USD"); }
            public List<OhlcBar> ohlc(String symbol, int days) {
                return List.of(new OhlcBar(java.time.LocalDate.parse("2025-01-02"),
                        new BigDecimal("9"), new BigDecimal("11"), new BigDecimal("8"), new BigDecimal("10"), 100L));
            }
        };
    }

    @Test
    void secondProviderWinsWhenFirstFails() {
        var svc = new MarketDataService(List.of(failing("a"), ok("b")), 1000, () -> 0L);
        assertThat(svc.quote("AAPL").price()).isEqualByComparingTo("10.00");
        assertThat(svc.ohlc("AAPL", 5)).hasSize(1);
    }

    @Test
    void quotesOmitsUnresolvedButKeepsResolved() {
        // ok provider resolves everything; ensure batch maps symbols (per-symbol cached)
        var svc = new MarketDataService(List.of(ok("b")), 1000, () -> 0L);
        Map<String, Quote> q = svc.quotes(List.of("AAPL", "MSFT"));
        assertThat(q).containsOnlyKeys("AAPL", "MSFT");
    }

    @Test
    void allProvidersFailingThrowsUnavailable() {
        var svc = new MarketDataService(List.of(failing("a"), failing("b")), 1000, () -> 0L);
        assertThatThrownBy(() -> svc.quote("AAPL"))
                .isInstanceOf(MarketDataException.class)
                .extracting(e -> ((MarketDataException) e).kind())
                .isEqualTo(MarketDataException.Kind.UNAVAILABLE);
    }

    @Test
    void quotesOmitsOnlyTheFailedSymbol() {
        // Provider succeeds for "AAPL" but throws UNAVAILABLE for "BAD"
        MarketDataProvider provider = new MarketDataProvider() {
            public String name() { return "partial"; }
            public Quote quote(String symbol) {
                if ("BAD".equals(symbol)) {
                    throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "bad symbol", null);
                }
                return new Quote(symbol, new BigDecimal("10.00"), BigDecimal.ZERO, "USD");
            }
            public List<OhlcBar> ohlc(String symbol, int days) { throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "n/a", null); }
        };
        var svc = new MarketDataService(List.of(provider), 1000, () -> 0L);
        Map<String, Quote> result = svc.quotes(List.of("AAPL", "BAD"));
        assertThat(result).containsKey("AAPL");
        assertThat(result).doesNotContainKey("BAD");
    }
}
