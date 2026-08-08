package de.visterion.agora.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private final InstrumentResolver resolver;
    private final TtlCache<String, List<OhlcBar>> ohlcCache;
    private final TtlCache<String, Quote> quoteCache;
    private final TtlCache<String, Boolean> ohlcNotFoundCache;
    private final TtlCache<String, Boolean> quoteNotFoundCache;

    /**
     * Spring-wired constructor.
     *
     * @param providers      ordered list of market-data providers (first success wins)
     * @param quoteTtlSeconds cache TTL in <strong>seconds</strong> for quotes (bound to
     *                        {@code agora.data.cache.ttl.quote-seconds}) — kept separate from the
     *                        ohlc TTL so watchlist/kill-criteria/depot quote polling can use a
     *                        longer TTL without staling GUI ohlc charts.
     * @param ttlSeconds     cache TTL in <strong>seconds</strong> for ohlc (bound to
     *                        {@code agora.data.cache.ttl-seconds})
     * @param resolver       resolves caller input into a canonical {@link Instrument}
     */
    @Autowired
    public MarketDataService(List<MarketDataProvider> providers,
                             @Value("${agora.data.cache.ttl.quote-seconds:300}") long quoteTtlSeconds,
                             @Value("${agora.data.cache.ttl-seconds:120}") long ttlSeconds,
                             InstrumentResolver resolver) {
        this(providers, quoteTtlSeconds * 1000L, ttlSeconds * 1000L, System::currentTimeMillis, resolver);
    }

    /**
     * Back-compat constructor (no resolver) — defaults to a pass-through resolver so existing
     * callers/tests keep today's string-in/string-out behaviour. Both caches share one TTL,
     * matching pre-split behaviour.
     *
     * @param providers   ordered list of market-data providers (first success wins)
     * @param ttlSeconds  cache TTL in <strong>seconds</strong>, applied to both quote and ohlc caches
     */
    public MarketDataService(List<MarketDataProvider> providers, long ttlSeconds) {
        this(providers, ttlSeconds * 1000L, ttlSeconds * 1000L, System::currentTimeMillis, Instrument::raw);
    }

    /**
     * Test constructor with injectable clock (pass-through resolver). Both caches share one TTL,
     * matching pre-split behaviour.
     *
     * @param providers   ordered list of market-data providers
     * @param ttlMillis   cache TTL in <strong>milliseconds</strong>, applied to both quote and ohlc caches
     * @param now         time source (injectable for deterministic tests)
     */
    MarketDataService(List<MarketDataProvider> providers, long ttlMillis, LongSupplier now) {
        this(providers, ttlMillis, ttlMillis, now, Instrument::raw);
    }

    /**
     * Test constructor with independent quote/ohlc TTLs and injectable clock (pass-through resolver).
     *
     * @param providers      ordered list of market-data providers
     * @param quoteTtlMillis cache TTL in <strong>milliseconds</strong> for quotes
     * @param ohlcTtlMillis  cache TTL in <strong>milliseconds</strong> for ohlc
     * @param now            time source (injectable for deterministic tests)
     */
    MarketDataService(List<MarketDataProvider> providers, long quoteTtlMillis, long ohlcTtlMillis, LongSupplier now) {
        this(providers, quoteTtlMillis, ohlcTtlMillis, now, Instrument::raw);
    }

    /**
     * Test constructor with injectable clock and resolver.
     *
     * @param providers   ordered list of market-data providers
     * @param ttlMillis   cache TTL in <strong>milliseconds</strong>, applied to both quote and ohlc caches
     * @param now         time source (injectable for deterministic tests)
     * @param resolver    resolves caller input into a canonical {@link Instrument}
     */
    MarketDataService(List<MarketDataProvider> providers, long ttlMillis, LongSupplier now, InstrumentResolver resolver) {
        this(providers, ttlMillis, ttlMillis, now, resolver);
    }

    /**
     * Master constructor: independent quote/ohlc TTLs, injectable clock and resolver.
     *
     * @param providers      ordered list of market-data providers
     * @param quoteTtlMillis cache TTL in <strong>milliseconds</strong> for quotes
     * @param ohlcTtlMillis  cache TTL in <strong>milliseconds</strong> for ohlc
     * @param now            time source (injectable for deterministic tests)
     * @param resolver       resolves caller input into a canonical {@link Instrument}
     */
    MarketDataService(List<MarketDataProvider> providers, long quoteTtlMillis, long ohlcTtlMillis,
                       LongSupplier now, InstrumentResolver resolver) {
        this.providers = List.copyOf(providers);
        this.resolver = resolver;
        this.ohlcCache = new TtlCache<>(ohlcTtlMillis, 4096, now);
        this.quoteCache = new TtlCache<>(quoteTtlMillis, 4096, now);
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
            return quoteCache.get(key, () -> {
                Instrument inst = resolver.resolve(symbol);
                return firstSuccess(p -> p.quote(inst), "quote " + symbol, allNotFound, inst);
            });
        } catch (MarketDataException e) {
            cacheNegativeIfApplicable(quoteNotFoundCache, key, allNotFound[0], e);
            throw e;
        }
    }

    /** The one and only {@code ohlcCache}/{@code ohlcNotFoundCache} key shape: uppercased caller
     *  symbol + ":" + days. {@link #ohlcBatch} writes under exactly this key so a later
     *  {@link #ohlc} for the same symbol/days is a cache hit rather than a fresh fetch. */
    private static String ohlcKey(String symbol, int days) {
        return symbol.toUpperCase(Locale.ROOT) + ":" + days;
    }

    public List<OhlcBar> ohlc(String symbol, int days) {
        String key = ohlcKey(symbol, days);
        if (ohlcNotFoundCache.isFresh(key)) {
            throw notFoundCached("ohlc " + symbol);
        }
        boolean[] allNotFound = {true};
        try {
            return ohlcCache.get(key, () -> {
                Instrument inst = resolver.resolve(symbol);
                return firstSuccess(p -> p.ohlc(inst, days), "ohlc " + symbol, allNotFound, inst);
            });
        } catch (MarketDataException e) {
            cacheNegativeIfApplicable(ohlcNotFoundCache, key, allNotFound[0], e);
            throw e;
        }
    }

    /**
     * Daily bars for many symbols with as few provider round-trips as possible: the warm
     * {@link #ohlcCache} is consulted first and only the misses go to the first batch-capable
     * provider in the chain (today: Alpaca) in one multi-symbol request per chunk.
     *
     * <p>Everything the provider returns is written back into the <em>same</em> {@code ohlcCache}
     * under the same key {@link #ohlc} uses, so a following {@code ohlc(symbol, days)} is served
     * from the cache without a fetch. That write-back is the reason this method lives in the
     * service and not in the calling tool.
     *
     * <p>Symbols the batch provider does not serve are simply absent from the result — they are
     * <strong>not</strong> retried one by one. A silent per-symbol fallback would restore exactly
     * the ~490-single-call storm this method exists to remove; the caller sees who is missing and
     * decides. Symbols the batch provider cannot serve at all ({@code canServe} false, e.g. non-US
     * suffixes) are never even requested, and a fresh negative cache entry short-circuits a symbol
     * the same way it does in {@code ohlc}.
     *
     * @return bars per requested symbol, in request order, containing only the symbols served
     */
    public Map<String, List<OhlcBar>> ohlcBatch(List<String> symbols, int days) {
        MarketDataProvider batchProvider = providers.stream()
                .filter(MarketDataProvider::supportsOhlcBatch)
                .findFirst().orElse(null);

        Map<String, List<OhlcBar>> byCallerSymbol = new LinkedHashMap<>();
        Map<String, String> requestToCaller = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (String symbol : symbols) {
            if (symbol == null || symbol.isBlank() || !seen.add(symbol)) continue;
            String key = ohlcKey(symbol, days);
            if (ohlcNotFoundCache.isFresh(key)) continue;
            var cached = ohlcCache.peek(key);
            if (cached.isPresent()) {
                byCallerSymbol.put(symbol, cached.get());
                continue;
            }
            if (batchProvider == null) continue;
            Instrument inst = resolver.resolve(symbol);
            if (!batchProvider.canServe(inst)) continue;
            requestToCaller.putIfAbsent(inst.displaySymbol(), symbol);
        }

        if (batchProvider != null && !requestToCaller.isEmpty()) {
            Map<String, List<OhlcBar>> fetched;
            try {
                fetched = batchProvider.ohlcBatch(List.copyOf(requestToCaller.keySet()), days);
            } catch (RuntimeException e) {
                // Same fail-soft contract as the single path: a down provider degrades the answer,
                // it does not blow up the caller. The misses stay missing.
                log.warn("batch ohlc via {} failed: {}", batchProvider.name(), e.toString());
                fetched = Map.of();
            }
            for (Map.Entry<String, List<OhlcBar>> e : fetched.entrySet()) {
                String caller = requestToCaller.get(e.getKey());
                if (caller == null || e.getValue().isEmpty()) continue;
                ohlcCache.put(ohlcKey(caller, days), e.getValue());
                byCallerSymbol.put(caller, e.getValue());
            }
        }

        // Re-emit in request order — cache hits and fresh fetches interleave otherwise.
        Map<String, List<OhlcBar>> out = new LinkedHashMap<>();
        for (String symbol : symbols) {
            List<OhlcBar> bars = byCallerSymbol.get(symbol);
            if (bars != null) out.putIfAbsent(symbol, bars);
        }
        return out;
    }

    /** Batch quotes; per-symbol cached via quote() (which also consults the negative cache
     *  per symbol before walking providers). Failures are REPORTED, not swallowed: the kind
     *  used to be discarded here, which made "symbol does not exist" and "provider is down"
     *  indistinguishable for every caller. Keys are the raw request symbols. */
    public QuoteBatch quotes(Collection<String> symbols) {
        Map<String, Quote> resolved = new LinkedHashMap<>();
        Map<String, MarketDataException.Kind> failed = new LinkedHashMap<>();
        for (String s : symbols) {
            try {
                resolved.put(s, quote(s));
            } catch (MarketDataException e) {
                failed.put(s, e.kind());
            }
        }
        return new QuoteBatch(resolved, failed);
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
     * @param inst         the resolved instrument; providers that declare themselves unable to
     *                     serve it ({@link MarketDataProvider#canServe(Instrument)} false) are
     *                     skipped entirely — a skip does not touch {@code allNotFound}.
     *
     * <h4>Why the chain trace is DEBUG and not WARN</h4>
     * A silent fallback used to leave nothing behind: a typed {@link MarketDataException} from a
     * provider was swallowed without a word, so "Alpaca failed, Yahoo answered" was invisible.
     * The HTTP-visible half of that is in fact already recorded at INFO by
     * {@link de.visterion.agora.observability.ProviderCallLogger} — one
     * {@code provider_call provider=… status=…} line per outbound call, failures included. What
     * it structurally cannot see is added here: a read/connect timeout throws inside
     * {@code execution.execute} before its emit is reached, a keyless self-skip makes no HTTP call
     * at all, and a 200 carrying an unusable payload logs as {@code status=200}. Nor does it know
     * anything about the chain — which provider ultimately served.
     *
     * <p>DEBUG rather than WARN because a keyless provider self-skips with {@code UNAVAILABLE} on
     * <em>every single call</em> (TwelveData/Finnhub without a key — see {@code ProviderFallbackTest}),
     * so at WARN a normal, healthy deployment would emit several warnings per quote and a
     * market-wide lazarus pass thousands. Same demotion, same reason as
     * {@code NewsAggregatorCooldownLoggingTest} pins for the news chain. Turn it on per hunt with
     * {@code logging.level.de.visterion.agora.data.MarketDataService=DEBUG}.
     */
    private <T> T firstSuccess(Function<MarketDataProvider, T> call, String what, boolean[] allNotFound,
                                 Instrument inst) {
        MarketDataException last = null;
        List<String> failures = new ArrayList<>();
        for (MarketDataProvider p : providers) {
            if (!p.canServe(inst)) {
                continue;
            }
            try {
                T result = call.apply(p);
                if (!failures.isEmpty()) {
                    log.debug("{} served by {} after {}", what, p.name(), failures);
                }
                return result;
            } catch (MarketDataException e) {
                last = e;
                if (e.kind() != MarketDataException.Kind.NOT_FOUND) {
                    allNotFound[0] = false;
                }
                failures.add(p.name() + "(" + e.kind() + ")");
                log.debug("provider {} failed for {}: {} {}", p.name(), what, e.kind(), e.getMessage());
            } catch (RuntimeException e) {
                // Stays WARN: an unexpected non-MarketDataException (NPE on a malformed response)
                // is a bug signal, not the routine chain traffic the DEBUG lines above cover.
                log.warn("provider {} failed for {}: {}", p.name(), what, e.toString());
                last = new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                        p.name() + " failed: " + e.getMessage(), e);
                allNotFound[0] = false;
                failures.add(p.name() + "(" + MarketDataException.Kind.UNAVAILABLE + ")");
            }
        }
        if (last != null) {
            throw last;
        }
        throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                "no provider could serve " + what, null);
    }
}
