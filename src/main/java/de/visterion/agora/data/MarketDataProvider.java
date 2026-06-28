package de.visterion.agora.data;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A pluggable market-data source. Implement as a @Component to join the fallback chain. */
public interface MarketDataProvider {
    String name();
    Quote quote(String symbol);
    List<OhlcBar> ohlc(String symbol, int days);

    /** Batch quotes; default resolves per-symbol and omits symbols that fail. */
    default Map<String, Quote> quotes(Collection<String> symbols) {
        Map<String, Quote> out = new LinkedHashMap<>();
        for (String s : symbols) {
            try { out.put(s, quote(s)); }
            catch (MarketDataException ignored) { /* omit */ }
        }
        return out;
    }
}
