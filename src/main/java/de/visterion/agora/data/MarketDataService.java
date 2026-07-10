package de.visterion.agora.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Tries providers in order (fallback); caches successful ohlc/quote results (TTL). */
@Component
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    /** Fixed TTL for the negative (all-providers-NOT_FOUND) cache — independent of the
     *  configured positive-result TTL, kept short so a symbol that starts resolving later
     *  (e.g. a new listing) is not blocked for the full positive-cache duration. */
    private static final long NEGATIVE_CACHE_TTL_MILLIS = 60_000L;
    private static final long NEGATIVE_CACHE_MAX_SIZE = 4096;

    private final List<MarketDataProvider> providers;
    private final TtlCache<String, List<OhlcBar>> ohlcCache;
    private final TtlCache<String, Quote> quoteCache;
    private final TtlCache<String, Boolean> ohlcNotFoundCache;
    private final TtlCache<String, Boolean> quoteNotFoundCache;

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
        this.ohlcCache = new TtlCache<>(ttlMillis, 4096, now);
        this.quoteCache = new TtlCache<>(ttlMillis, 4096, now);
        this.ohlcNotFoundCache = new TtlCache<>(NEGATIVE_CACHE_TTL_MILLIS, NEGATIVE_CACHE_MAX_SIZE, now);
        this.quoteNotFoundCache = new TtlCache<>(NEGATIVE_CACHE_TTL_MILLIS, NEGATIVE_CACHE_MAX_SIZE, now);
    }

    public Quote quote(String symbol) {
        String key = symbol.toUpperCase(Locale.ROOT);
        if (quoteNotFoundCache.isFresh(key)) {
            throw notFoundCached("quote " + symbol);
        }
        boolean[] allNotFound = {true};
        try {
            return quoteCache.get(key,
                    () -> firstSuccess(p -> p.quote(symbol), "quote " + symbol, allNotFound));
        } catch (MarketDataException e) {
            cacheNegativeIfApplicable(quoteNotFoundCache, key, allNotFound[0], e);
            throw e;
        }
    }

    public List<OhlcBar> ohlc(String symbol, int days) {
        String key = symbol.toUpperCase(Locale.ROOT) + ":" + days;
        if (ohlcNotFoundCache.isFresh(key)) {
            throw notFoundCached("ohlc " + symbol);
        }
        boolean[] allNotFound = {true};
        try {
            return ohlcCache.get(key,
                    () -> firstSuccess(p -> p.ohlc(symbol, days), "ohlc " + symbol, allNotFound));
        } catch (MarketDataException e) {
            cacheNegativeIfApplicable(ohlcNotFoundCache, key, allNotFound[0], e);
            throw e;
        }
    }

    /** Batch quotes; per-symbol cached via quote() (which also consults the negative cache
     *  per symbol before walking providers). Symbols that fail are omitted. */
    public Map<String, Quote> quotes(Collection<String> symbols) {
        Map<String, Quote> out = new LinkedHashMap<>();
        for (String s : symbols) {
            try { out.put(s, quote(s)); }
            catch (MarketDataException ignored) { /* omit */ }
        }
        return out;
    }

    private static MarketDataException notFoundCached(String what) {
        return new MarketDataException(MarketDataException.Kind.NOT_FOUND,
                "no provider could serve " + what + " (cached NOT_FOUND)", null);
    }

    private static void cacheNegativeIfApplicable(TtlCache<String, Boolean> negativeCache, String key,
                                                    boolean allNotFound, MarketDataException e) {
        if (allNotFound && e.kind() == MarketDataException.Kind.NOT_FOUND) {
            negativeCache.get(key, () -> Boolean.TRUE);
        }
    }

    /**
     * Walks providers in order, returning the first success. Catches {@link RuntimeException}
     * (not just {@link MarketDataException}) per provider so an unexpected error from one
     * provider (e.g. an NPE from a malformed response) does not abort the fallback chain — the
     * remaining providers are still tried. If every provider fails, the last exception is
     * rethrown (wrapped as {@code UNAVAILABLE} if it wasn't already a {@link MarketDataException}).
     *
     * @param allNotFound out-parameter (single-element array): set to {@code false} as soon as
     *                     any provider fails with something other than {@code NOT_FOUND}; stays
     *                     {@code true} only if every attempted provider answered NOT_FOUND.
     */
    private <T> T firstSuccess(Function<MarketDataProvider, T> call, String what, boolean[] allNotFound) {
        MarketDataException last = null;
        for (MarketDataProvider p : providers) {
            try {
                return call.apply(p);
            } catch (MarketDataException e) {
                last = e;
                if (e.kind() != MarketDataException.Kind.NOT_FOUND) {
                    allNotFound[0] = false;
                }
            } catch (RuntimeException e) {
                log.warn("provider {} failed for {}: {}", p.name(), what, e.toString());
                last = new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                        p.name() + " failed: " + e.getMessage(), e);
                allNotFound[0] = false;
            }
        }
        if (last != null) {
            throw last;
        }
        throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                "no provider could serve " + what, null);
    }
}
