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

    /** Identity-aware entry points. Default = today's string path via displaySymbol.
     *  Only providers that consume the canonical identity (Saxo→UIC) override these. */
    default Quote quote(Instrument inst) { return quote(inst.displaySymbol()); }
    default List<OhlcBar> ohlc(Instrument inst, int days) { return ohlc(inst.displaySymbol(), days); }

    /**
     * Whether this provider is a plausible source for {@code inst} at all. Default {@code true}
     * (every provider may be tried). US-only providers (Alpaca, TwelveData, Finnhub) override
     * this to skip non-US instruments so {@link MarketDataService#firstSuccess} doesn't waste a
     * round-trip on a guaranteed 4xx before falling through to a global provider (Saxo/Yahoo).
     */
    default boolean canServe(Instrument inst) { return true; }

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
