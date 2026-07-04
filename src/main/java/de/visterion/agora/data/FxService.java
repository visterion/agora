package de.visterion.agora.data;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.function.LongSupplier;

/** On-demand FX rates via Yahoo (PAIR=X), cached per-family. No amount conversion (that is a consumer concern). */
@Component
public class FxService {

    private final RestClient client;
    private final TtlCache<String, FxRate> cache;

    @Autowired
    public FxService(@Value("${agora.data.yahoo.base-url}") String baseUrl,
                     @Value("${agora.data.yahoo.user-agent}") String userAgent,
                     @Value("${agora.data.cache.ttl.prices-seconds:120}") long ttlSeconds) {
        this(baseUrl, userAgent, ttlSeconds, System::currentTimeMillis);
    }

    FxService(String baseUrl, String userAgent, long ttlSeconds, LongSupplier now) {
        this.client = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory())
                .baseUrl(baseUrl)
                .defaultHeader("User-Agent", userAgent)
                .build();
        this.cache = new TtlCache<>(ttlSeconds * 1000L, now);
    }

    public FxRate rate(String from, String to) {
        String f = from == null ? "" : from.toUpperCase();
        String t = to == null ? "" : to.toUpperCase();
        if (f.equals(t)) return new FxRate(f, t, BigDecimal.ONE);
        return cache.get("fx:" + f + t, () -> fetch(f, t));
    }

    private FxRate fetch(String f, String t) {
        String pair = f + t + "=X";
        JsonNode body;
        try {
            body = client.get()
                    .uri(uri -> uri.path("/v8/finance/chart/" + pair).build())
                    .retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "FX HTTP " + e.getStatusCode(), e);
        } catch (Exception e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "FX unreachable: " + e.getMessage(), e);
        }
        if (body == null) throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "empty FX body", null);
        JsonNode px = body.path("chart").path("result").path(0).path("meta").path("regularMarketPrice");
        if (px.isMissingNode() || px.isNull())
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "no rate for " + pair, null);
        return new FxRate(f, t, new BigDecimal(px.asString()));
    }
}
