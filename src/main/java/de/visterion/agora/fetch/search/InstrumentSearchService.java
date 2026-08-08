package de.visterion.agora.fetch.search;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.TtlCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * Instrument search with a TTL cache and a reactive cooldown.
 *
 * <p>Yahoo is also the last provider of the whole quote chain (YahooMarketDataProvider
 * \@Order(30)), so a throttled container IP would drag down the prices of every non-US symbol.
 * Protection is: this cache, the frontend debounce, and the cooldown — never a min-interval.
 */
@Component
public class InstrumentSearchService {

    /** Seam so tests can supply a stub without HTTP. */
    interface Upstream {
        List<SearchHit> search(String query, int quotesCount);
    }

    private static final int MAX_LIMIT = 25;
    private static final int MAX_QUOTES_COUNT = 30;

    private final Upstream upstream;
    private final TtlCache<String, List<SearchHit>> cache;
    private final SearchCooldown cooldown;

    @Autowired
    public InstrumentSearchService(
            YahooSearchClient client,
            @Value("${agora.data.cache.ttl.instrument-search-seconds:600}") long ttlSeconds,
            @Value("${agora.data.search.cooldown-threshold:3}") int cooldownThreshold,
            @Value("${agora.data.search.cooldown-ms:60000}") long cooldownMillis) {
        this(client::search, ttlSeconds * 1000L, cooldownThreshold, cooldownMillis,
             System::currentTimeMillis);
    }

    InstrumentSearchService(Upstream upstream, long ttlMillis, int cooldownThreshold,
                            long cooldownMillis, LongSupplier now) {
        this.upstream = upstream;
        this.cache = new TtlCache<>(ttlMillis, 2048, now);
        this.cooldown = new SearchCooldown(cooldownThreshold, cooldownMillis, now);
    }

    /**
     * Filtered, deduped and truncated hits for {@code query}.
     *
     * @throws MarketDataException UNAVAILABLE while cooled down, or when the upstream fails
     */
    public List<SearchHit> search(String query, int limit) {
        int effectiveLimit = Math.clamp(limit, 1, MAX_LIMIT);
        int quotesCount = Math.min(effectiveLimit * 3, MAX_QUOTES_COUNT);
        String key = query.trim().toLowerCase(Locale.ROOT) + "#" + quotesCount;

        if (cache.isFresh(key)) {
            return truncate(cache.get(key, List::of), effectiveLimit);
        }
        if (cooldown.isCooled()) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "instrument search cooling down after repeated upstream failures", null);
        }
        try {
            List<SearchHit> hits = cache.get(key, () -> upstream.search(query.trim(), quotesCount));
            cooldown.recordSuccess();
            return truncate(hits, effectiveLimit);
        } catch (MarketDataException e) {
            cooldown.recordFailure();
            throw e;
        }
    }

    private static List<SearchHit> truncate(List<SearchHit> hits, int limit) {
        return hits.size() <= limit ? hits : List.copyOf(hits.subList(0, limit));
    }
}
