package de.visterion.agora.fetch.earnings;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.TtlCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Earnings calendar with provider fallback (Finnhub → Yahoo), per-family TTL cache.
 *
 * <p>A provider returning an empty list is treated as "no answer", not success — the chain
 * tries the next provider. Only a non-empty result is cached. If every provider is either
 * unavailable or returns empty, {@link #firstSuccess} throws instead of returning/caching
 * an empty list, so a transient all-empty outcome (e.g. a quiet window queried right as a
 * provider's data lags) is retried on the next call rather than poisoning the cache for
 * the full TTL.
 */
@Component
public class EarningsService {

    private final List<EarningsProvider> providers;
    private final TtlCache<String, List<EarningsEvent>> cache;

    @Autowired
    public EarningsService(List<EarningsProvider> providers,
                           @Value("${agora.data.cache.ttl.fundamentals-seconds:21600}") long ttlSeconds) {
        this(providers, ttlSeconds, System::currentTimeMillis);
    }

    EarningsService(List<EarningsProvider> providers, long ttlSeconds, LongSupplier now) {
        this.providers = List.copyOf(providers);
        // Keyed by symbol+date-range, so cardinality grows with distinct windows queried.
        this.cache = new TtlCache<>(ttlSeconds * 1000L, 4096, now);
    }

    public List<EarningsEvent> earnings(String symbol, LocalDate from, LocalDate to) {
        return cache.get("earn:" + symbol + ":" + from + ":" + to, () -> firstSuccess(symbol, from, to));
    }

    /** Market-wide earnings for the window (no symbol). Distinct cache family from symbol lookups. */
    public List<EarningsEvent> earningsWindow(LocalDate from, LocalDate to) {
        return cache.get("earnwin:" + from + ":" + to, () -> firstSuccess(null, from, to));
    }

    private List<EarningsEvent> firstSuccess(String symbol, LocalDate from, LocalDate to) {
        MarketDataException last = null;
        for (EarningsProvider p : providers) {
            try {
                List<EarningsEvent> result = p.earnings(symbol, from, to);
                if (!result.isEmpty()) return result;
                // Empty is "no answer", not success — try the next provider.
            } catch (MarketDataException e) {
                last = e;
            }
        }
        if (last != null) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "no provider could serve earnings " + symbol, last);
        }
        // Every provider answered but none had data. Throw rather than cache an empty
        // list: a genuinely quiet window looks identical to a transient upstream gap,
        // and caching the latter for the full TTL would poison subsequent lookups.
        throw new MarketDataException(MarketDataException.Kind.NOT_FOUND,
                "no earnings data for " + symbol, null);
    }
}
