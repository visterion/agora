package de.visterion.agora.data;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tries providers in order; first success wins (fallback seam). */
@Component
public class MarketDataService {

    private final List<MarketDataProvider> providers;

    public MarketDataService(List<MarketDataProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    public Quote quote(String symbol) {
        return firstSuccess(p -> p.quote(symbol), "quote " + symbol);
    }

    public List<OhlcBar> ohlc(String symbol, int days) {
        return firstSuccess(p -> p.ohlc(symbol, days), "ohlc " + symbol);
    }

    /** Batch: merge each provider's batch result; later providers fill only missing symbols. */
    public Map<String, Quote> quotes(Collection<String> symbols) {
        Map<String, Quote> out = new LinkedHashMap<>();
        for (MarketDataProvider p : providers) {
            if (out.keySet().containsAll(symbols)) break;
            try {
                p.quotes(symbols).forEach(out::putIfAbsent);
            } catch (MarketDataException ignored) { /* try next */ }
        }
        return out;
    }

    private <T> T firstSuccess(java.util.function.Function<MarketDataProvider, T> call, String what) {
        MarketDataException last = null;
        for (MarketDataProvider p : providers) {
            try { return call.apply(p); }
            catch (MarketDataException e) { last = e; }
        }
        throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                "no provider could serve " + what, last);
    }
}
