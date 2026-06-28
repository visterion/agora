package de.visterion.agora.data;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Tries providers in order (fallback); caches successful ohlc/quote results (TTL). */
@Component
public class MarketDataService {

    private final List<MarketDataProvider> providers;
    private final TtlCache<String, List<OhlcBar>> ohlcCache;
    private final TtlCache<String, Quote> quoteCache;

    /**
     * Spring-wired constructor.
     *
     * @param providers   ordered list of market-data providers (first success wins)
     * @param ttlSeconds  cache TTL in <strong>seconds</strong> (bound to {@code agora.data.cache.ttl-seconds})
     */
    @Autowired
    public MarketDataService(List<MarketDataProvider> providers,
                             @Value("${agora.data.cache.ttl-seconds:120}") long ttlSeconds) {
        this(providers, ttlSeconds * 1000L, System::currentTimeMillis);
    }

    /**
     * Test constructor with injectable clock.
     *
     * @param providers   ordered list of market-data providers
     * @param ttlMillis   cache TTL in <strong>milliseconds</strong>
     * @param now         time source (injectable for deterministic tests)
     */
    MarketDataService(List<MarketDataProvider> providers, long ttlMillis, LongSupplier now) {
        this.providers = List.copyOf(providers);
        this.ohlcCache = new TtlCache<>(ttlMillis, now);
        this.quoteCache = new TtlCache<>(ttlMillis, now);
    }

    public Quote quote(String symbol) {
        return quoteCache.get("quote:" + symbol,
                () -> firstSuccess(p -> p.quote(symbol), "quote " + symbol));
    }

    public List<OhlcBar> ohlc(String symbol, int days) {
        return ohlcCache.get("ohlc:" + symbol + ":" + days,
                () -> firstSuccess(p -> p.ohlc(symbol, days), "ohlc " + symbol));
    }

    /** Batch quotes; per-symbol cached via quote(). Symbols that fail are omitted. */
    public Map<String, Quote> quotes(Collection<String> symbols) {
        Map<String, Quote> out = new LinkedHashMap<>();
        for (String s : symbols) {
            try { out.put(s, quote(s)); }
            catch (MarketDataException ignored) { /* omit */ }
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
