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
 * <p>A successful result is cached for the TTL — including an empty list from the Yahoo
 * fallback. This is not a live-reconfiguration hazard: provider keys are read from the
 * environment at startup, so a key added later only takes effect on restart, which builds
 * a fresh (empty) in-memory cache. Hence a cached-empty result never outlives the config
 * that produced it.
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
        this.cache = new TtlCache<>(ttlSeconds * 1000L, now);
    }

    public List<EarningsEvent> earnings(String symbol, LocalDate from, LocalDate to) {
        return cache.get("earn:" + symbol + ":" + from + ":" + to, () -> firstSuccess(symbol, from, to));
    }

    private List<EarningsEvent> firstSuccess(String symbol, LocalDate from, LocalDate to) {
        MarketDataException last = null;
        for (EarningsProvider p : providers) {
            try { return p.earnings(symbol, from, to); }
            catch (MarketDataException e) { last = e; }
        }
        throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                "no provider could serve earnings " + symbol, last);
    }
}
