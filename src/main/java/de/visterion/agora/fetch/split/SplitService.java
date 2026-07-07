package de.visterion.agora.fetch.split;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.TtlCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.LongSupplier;

/** Orchestrates split providers as an ordered fallback chain (first non-empty wins),
 *  cached (splits TTL). A provider that throws is skipped; all-empty yields empty. */
@Component
public class SplitService {

    private final List<SplitProvider> providers;
    private final TtlCache<String, List<SplitEvent>> cache;

    @Autowired
    public SplitService(List<SplitProvider> providers,
            @Value("${agora.data.cache.ttl.splits-seconds:21600}") long ttlSeconds) {
        this(providers, ttlSeconds * 1000L, System::currentTimeMillis);
    }

    SplitService(List<SplitProvider> providers, long ttlMillis, LongSupplier now) {
        this.providers = List.copyOf(providers);
        this.cache = new TtlCache<>(ttlMillis, now);
    }

    public List<SplitEvent> splits(String symbol) {
        return cache.get("split:" + symbol, () -> resolve(symbol));
    }

    private List<SplitEvent> resolve(String symbol) {
        for (SplitProvider p : providers) {
            try {
                List<SplitEvent> r = p.splits(symbol);
                if (r != null && !r.isEmpty()) return r;   // first non-empty wins
            } catch (MarketDataException e) {
                // provider unconfigured/unreachable → skip to next
            }
        }
        return List.of();
    }
}
