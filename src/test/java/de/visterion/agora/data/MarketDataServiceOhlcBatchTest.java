package de.visterion.agora.data;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Batch OHLC: cache-first, one provider round-trip for the misses, write-back under the
 *  single-symbol cache key. All fixture symbols/prices are synthetic. */
class MarketDataServiceOhlcBatchTest {

    /** Batch-capable stub that counts single and batch calls separately. */
    private static final class StubProvider implements MarketDataProvider {
        final List<List<String>> batchCalls = new ArrayList<>();
        final List<String> singleCalls = new ArrayList<>();
        final Map<String, List<OhlcBar>> served = new LinkedHashMap<>();

        public String name() { return "stub-batch"; }
        public Quote quote(String s) { return new Quote(s, BigDecimal.TEN, BigDecimal.ZERO, "USD"); }

        public List<OhlcBar> ohlc(String symbol, int days) {
            singleCalls.add(symbol);
            List<OhlcBar> bars = served.get(symbol);
            if (bars == null) {
                throw new MarketDataException(MarketDataException.Kind.NOT_FOUND, "no " + symbol, null);
            }
            return bars;
        }

        public boolean supportsOhlcBatch() { return true; }

        public Map<String, List<OhlcBar>> ohlcBatch(List<String> symbols, int days) {
            batchCalls.add(List.copyOf(symbols));
            Map<String, List<OhlcBar>> out = new LinkedHashMap<>();
            for (String s : symbols) {
                if (served.containsKey(s)) out.put(s, served.get(s));
            }
            return out;
        }
    }

    private static List<OhlcBar> bars(String close) {
        BigDecimal c = new BigDecimal(close);
        return List.of(new OhlcBar(LocalDate.parse("2025-01-02"), c, c, c, c, 1000L));
    }

    @Test void fetchesMissesOnceAndOmitsUnservedSymbols() {
        StubProvider p = new StubProvider();
        p.served.put("SYNA", bars("10"));
        p.served.put("SYNB", bars("20"));
        MarketDataService svc = new MarketDataService(List.of(p), 120L);

        Map<String, List<OhlcBar>> out = svc.ohlcBatch(List.of("SYNA", "SYNB", "SYNGAP"), 30);

        assertThat(out).containsOnlyKeys("SYNA", "SYNB");
        assertThat(p.batchCalls).containsExactly(List.of("SYNA", "SYNB", "SYNGAP"));
        // no silent per-symbol retry for the symbol the batch didn't serve
        assertThat(p.singleCalls).isEmpty();
    }

    @Test void warmCacheSymbolsAreServedWithoutAProviderCall() {
        StubProvider p = new StubProvider();
        p.served.put("SYNA", bars("10"));
        p.served.put("SYNB", bars("20"));
        MarketDataService svc = new MarketDataService(List.of(p), 120L);

        svc.ohlc("SYNA", 30);                       // warms the cache the single-symbol way
        assertThat(p.singleCalls).containsExactly("SYNA");

        Map<String, List<OhlcBar>> out = svc.ohlcBatch(List.of("SYNA", "SYNB"), 30);

        assertThat(out).containsOnlyKeys("SYNA", "SYNB");
        // only the miss went to the provider
        assertThat(p.batchCalls).containsExactly(List.of("SYNB"));
    }

    @Test void batchWriteBackMakesTheFollowingSingleCallACacheHit() {
        StubProvider p = new StubProvider();
        p.served.put("SYNA", bars("10"));
        MarketDataService svc = new MarketDataService(List.of(p), 120L);

        svc.ohlcBatch(List.of("SYNA"), 30);
        List<OhlcBar> single = svc.ohlc("SYNA", 30);

        assertThat(single).isEqualTo(bars("10"));
        assertThat(p.singleCalls).isEmpty();        // served purely from the batch write-back
        assertThat(p.batchCalls).hasSize(1);
    }

    /** The write-back key includes `days`: a different window must not be answered from it. */
    @Test void aDifferentDaysWindowIsNotServedFromTheBatchWriteBack() {
        StubProvider p = new StubProvider();
        p.served.put("SYNA", bars("10"));
        MarketDataService svc = new MarketDataService(List.of(p), 120L);

        svc.ohlcBatch(List.of("SYNA"), 30);
        svc.ohlc("SYNA", 60);

        assertThat(p.singleCalls).containsExactly("SYNA");
    }

    @Test void symbolIsMatchedCaseInsensitivelyAgainstTheCache() {
        StubProvider p = new StubProvider();
        p.served.put("SYNA", bars("10"));
        MarketDataService svc = new MarketDataService(List.of(p), 120L);

        svc.ohlcBatch(List.of("SYNA"), 30);
        svc.ohlc("syna", 30);

        assertThat(p.singleCalls).isEmpty();
    }

    @Test void noBatchCapableProviderYieldsAnEmptyResultAndNoSingleCallStorm() {
        MarketDataProvider single = new MarketDataProvider() {
            public String name() { return "single-only"; }
            public Quote quote(String s) { return new Quote(s, BigDecimal.TEN, BigDecimal.ZERO, "USD"); }
            public List<OhlcBar> ohlc(String s, int d) { return bars("10"); }
        };
        MarketDataService svc = new MarketDataService(List.of(single), 120L);

        assertThat(svc.ohlcBatch(List.of("SYNA", "SYNB"), 30)).isEmpty();
    }

    @Test void aThrowingBatchProviderDegradesToWhatTheCacheHolds() {
        MarketDataProvider broken = new MarketDataProvider() {
            public String name() { return "broken"; }
            public Quote quote(String s) { return new Quote(s, BigDecimal.TEN, BigDecimal.ZERO, "USD"); }
            public List<OhlcBar> ohlc(String s, int d) { return bars("10"); }
            public boolean supportsOhlcBatch() { return true; }
            public Map<String, List<OhlcBar>> ohlcBatch(List<String> symbols, int days) {
                throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "down", null);
            }
        };
        MarketDataService svc = new MarketDataService(List.of(broken), 120L);
        svc.ohlc("SYNA", 30);                       // warm one symbol

        Map<String, List<OhlcBar>> out = svc.ohlcBatch(List.of("SYNA", "SYNB"), 30);

        assertThat(out).containsOnlyKeys("SYNA");
    }

    @Test void resultKeepsRequestOrder() {
        StubProvider p = new StubProvider();
        p.served.put("SYNA", bars("10"));
        p.served.put("SYNB", bars("20"));
        p.served.put("SYNC", bars("30"));
        MarketDataService svc = new MarketDataService(List.of(p), 120L);
        svc.ohlc("SYNB", 30);                       // warm the middle one only

        assertThat(svc.ohlcBatch(List.of("SYNA", "SYNB", "SYNC"), 30).keySet())
                .containsExactly("SYNA", "SYNB", "SYNC");
    }
}
