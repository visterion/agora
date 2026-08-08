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
        QuoteBatch batch = svc.quotes(List.of("AAPL", "MSFT"));
        assertThat(batch.resolved()).containsOnlyKeys("AAPL", "MSFT");
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
    void nonMarketDataExceptionFromOneProviderDoesNotAbortChain() {
        // M-D1: a provider throwing a plain RuntimeException (e.g. NPE) must not abort the
        // fallback chain — the next provider should still be consulted.
        MarketDataProvider npeProvider = new MarketDataProvider() {
            public String name() { return "broken"; }
            public Quote quote(String symbol) { throw new NullPointerException("boom"); }
            public List<OhlcBar> ohlc(String symbol, int days) { throw new NullPointerException("boom"); }
        };
        var svc = new MarketDataService(List.of(npeProvider, ok("b")), 1000, () -> 0L);
        assertThat(svc.quote("AAPL").price()).isEqualByComparingTo("10.00");
        assertThat(svc.ohlc("AAPL", 5)).hasSize(1);
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
        QuoteBatch batch = svc.quotes(List.of("AAPL", "BAD"));
        assertThat(batch.resolved()).containsKey("AAPL");
        assertThat(batch.resolved()).doesNotContainKey("BAD");
        assertThat(batch.failed()).containsEntry("BAD", MarketDataException.Kind.UNAVAILABLE);
    }

    @Test
    void quotesKeysFailedReasonByTheRawRequestSymbolNotAnUppercasedCacheKey() {
        // Regression guard for rule 2: QuoteBatch.failed() must be keyed by exactly the string
        // the caller passed in, never by quote()'s internal uppercased cache key. A caller that
        // requests "nokia" and looks the reason up under "nokia" must find it — keying under
        // "NOKIA" would make the reason set empty and turn a real outage into "symbol not found"
        // at the tool layer. (Fails if failed.put(s, ...) is changed to
        // failed.put(s.toUpperCase(Locale.ROOT), ...) in MarketDataService.quotes.)
        MarketDataProvider provider = new MarketDataProvider() {
            public String name() { return "notfound"; }
            public Quote quote(String symbol) {
                throw new MarketDataException(MarketDataException.Kind.NOT_FOUND, "nf", null);
            }
            public List<OhlcBar> ohlc(String symbol, int days) { throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "n/a", null); }
        };
        var svc = new MarketDataService(List.of(provider), 1000, () -> 0L);
        QuoteBatch batch = svc.quotes(List.of("nokia"));
        assertThat(batch.failed()).containsEntry("nokia", MarketDataException.Kind.NOT_FOUND);
    }

    @Test
    void quoteFailureKindIsTheLastProviderTriedNotAnEarlierOne() {
        // The kind that reaches quotes()/failed() is the LAST provider's in the fallback chain:
        // an UNAVAILABLE thrown by an earlier provider (e.g. Saxo without a uic) must not mask a
        // later NOT_FOUND, and must not turn a genuine "symbol does not exist" into an outage.
        MarketDataProvider unavailableFirst = failing("first");
        MarketDataProvider notFoundLast = new MarketDataProvider() {
            public String name() { return "last"; }
            public Quote quote(String symbol) { throw new MarketDataException(MarketDataException.Kind.NOT_FOUND, "nf", null); }
            public List<OhlcBar> ohlc(String symbol, int days) { throw new MarketDataException(MarketDataException.Kind.NOT_FOUND, "nf", null); }
        };
        var svc = new MarketDataService(List.of(unavailableFirst, notFoundLast), 1000, () -> 0L);
        QuoteBatch batch = svc.quotes(List.of("NOKIA"));
        assertThat(batch.failed()).containsEntry("NOKIA", MarketDataException.Kind.NOT_FOUND);
    }
}
