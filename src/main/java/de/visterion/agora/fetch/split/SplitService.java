package de.visterion.agora.fetch.split;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.TtlCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Orchestrates split providers, merging every provider's answer (union by execution date,
 *  Alpaca wins on date conflicts) rather than stopping at the first non-empty result — Alpaca's
 *  corporate-actions history starts around 2015, so relying on it alone would silently drop
 *  pre-2015 splits that Finnhub (or another provider) still has. Cached (splits TTL). A
 *  provider that throws is skipped; if at least one provider answered, all-empty yields empty
 *  (cached). If every provider threw, resolve() throws instead of returning empty, so the
 *  failure is not cached and gets retried. */
@Component
public class SplitService {

    private static final Logger log = LoggerFactory.getLogger(SplitService.class);
    private static final String PREFERRED_PROVIDER = "alpaca";

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
        Map<String, List<SplitEvent>> byProvider = new LinkedHashMap<>();
        for (SplitProvider p : providers) {
            try {
                List<SplitEvent> r = p.splits(symbol);
                anyAnswered = true;
                if (r != null && !r.isEmpty()) byProvider.put(p.name(), r);
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
        if (byProvider.isEmpty()) return List.of();

        // Union by execution date; the preferred provider (Alpaca) wins conflicts since it is
        // the more authoritative source when both providers report a split on the same date.
        Map<LocalDate, SplitEvent> merged = new LinkedHashMap<>();
        for (var entry : byProvider.entrySet()) {
            if (PREFERRED_PROVIDER.equals(entry.getKey())) continue;
            for (SplitEvent ev : entry.getValue()) merged.put(ev.date(), ev);
        }
        for (SplitEvent ev : byProvider.getOrDefault(PREFERRED_PROVIDER, List.of())) {
            merged.put(ev.date(), ev);
        }
        List<SplitEvent> out = new ArrayList<>(merged.values());
        out.sort(Comparator.comparing(SplitEvent::date));
        return out;
    }
}
