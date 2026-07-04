package de.visterion.agora.fetch.finnhub;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.TtlCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.function.LongSupplier;

/** Fundamental metrics for a symbol via Finnhub (whole `metric` object passthrough), cached per-family. */
@Component
public class FundamentalsService {

    private final FinnhubClient client;
    private final TtlCache<String, Fundamentals> cache;

    @Autowired
    public FundamentalsService(FinnhubClient client,
                               @Value("${agora.data.cache.ttl.fundamentals-seconds:21600}") long ttlSeconds) {
        this(client, ttlSeconds, System::currentTimeMillis);
    }

    FundamentalsService(FinnhubClient client, long ttlSeconds, LongSupplier now) {
        this.client = client;
        this.cache = new TtlCache<>(ttlSeconds * 1000L, now);
    }

    public Fundamentals fundamentals(String symbol) {
        if (!client.configured())
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "finnhub: no api key", null);
        return cache.get("fund:" + symbol, () -> fetch(symbol));
    }

    private Fundamentals fetch(String symbol) {
        JsonNode body;
        try {
            body = client.http().get()
                    .uri(uri -> uri.path("/stock/metric")
                            .queryParam("symbol", symbol)
                            .queryParam("metric", "all")
                            .queryParam("token", client.token())
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "finnhub fundamentals unreachable: " + e.getMessage(), e);
        }
        if (body == null)
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "no fundamentals for " + symbol, null);
        JsonNode m = body.path("metric");
        if (m.isMissingNode() || !m.isObject())
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "no fundamentals for " + symbol, null);
        return new Fundamentals(symbol, m);
    }
}
