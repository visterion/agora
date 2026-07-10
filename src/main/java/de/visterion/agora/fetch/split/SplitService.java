package de.visterion.agora.fetch.split;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.TtlCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.LongSupplier;

/** Orchestrates split providers as an ordered fallback chain (first non-empty wins),
 *  cached (splits TTL). A provider that throws is skipped; if at least one provider
 *  answered, all-empty yields empty (cached). If every provider threw, resolve()
 *  throws instead of returning empty, so the failure is not cached and gets retried. */
@Component
public class SplitService {

    private static final Logger log = LoggerFactory.getLogger(SplitService.class);

    private final List<SplitProvider> providers;
    private final TtlCache<String, List<SplitEvent>> cache;

    @Autowired
    public SplitService(List<SplitProvider> providers,
            @Value("${agora.data.cache.ttl.splits-seconds:21600}") long ttlSeconds) {
        this(providers, ttlSeconds * 1000L, System::currentTimeMillis);
    }

    SplitService(List<SplitProvider> providers, long ttlMillis, LongSupplier now) {
        this.providers = List.copyOf(providers);
        this.cache = new TtlCache<>(ttlMillis, 4096, now);
    }

    public List<SplitEvent> splits(String symbol) {
        return cache.get("split:" + symbol, () -> resolve(symbol));
    }

    private List<SplitEvent> resolve(String symbol) {
        boolean anyAnswered = false;
        for (SplitProvider p : providers) {
            try {
                List<SplitEvent> r = p.splits(symbol);
                anyAnswered = true;
                if (r != null && !r.isEmpty()) return r;   // first non-empty wins
            } catch (MarketDataException e) {
                // provider unconfigured/unreachable → skip to next
            } catch (RuntimeException e) {
                log.warn("split provider {} failed for {}: {}", p.name(), symbol, e.toString());
                // treat as unreachable → skip to next
            }
        }
        if (!anyAnswered)
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "no split provider could answer " + symbol, null);
        return List.of();
    }
}
