package de.visterion.agora.fetch.finnhub;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.TtlCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.function.LongSupplier;

/** Company profile for a symbol via Finnhub /stock/profile2 (whole object passthrough), cached per-family. */
@Component
public class ProfileService {

    private final FinnhubClient client;
    private final TtlCache<String, Profile> cache;

    @Autowired
    public ProfileService(FinnhubClient client,
                          @Value("${agora.data.cache.ttl.fundamentals-seconds:21600}") long ttlSeconds) {
        this(client, ttlSeconds, System::currentTimeMillis);
    }

    ProfileService(FinnhubClient client, long ttlSeconds, LongSupplier now) {
        this.client = client;
        this.cache = new TtlCache<>(ttlSeconds * 1000L, now);
    }

    public Profile profile(String symbol) {
        if (!client.configured())
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "finnhub: no api key", null);
        return cache.get("profile:" + symbol, () -> fetch(symbol));
    }

    private Profile fetch(String symbol) {
        JsonNode body;
        try {
            body = client.http().get()
                    .uri(uri -> uri.path("/stock/profile2")
                            .queryParam("symbol", symbol)
                            .queryParam("token", client.token())
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "finnhub profile unreachable: " + e.getMessage(), e);
        }
        if (body == null || !body.isObject() || body.isEmpty())
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "no profile for " + symbol, null);
        return new Profile(symbol, body);
    }
}
