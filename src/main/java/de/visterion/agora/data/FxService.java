package de.visterion.agora.data;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.function.LongSupplier;

/** On-demand FX rates via Yahoo (PAIR=X), cached per-family. No amount conversion (that is a consumer concern). */
@Component
public class FxService {

    private final RestClient client;
    private final TtlCache<String, FxRate> cache;

    @Autowired
    public FxService(@Value("${agora.data.yahoo.base-url}") String baseUrl,
                     @Value("${agora.data.yahoo.user-agent}") String userAgent,
                     @Value("${agora.data.cache.ttl.prices-seconds:120}") long ttlSeconds,
                     @Value("${agora.data.provider-timeout-ms:4000}") long timeoutMs) {
        this(baseUrl, userAgent, ttlSeconds, timeoutMs, System::currentTimeMillis);
    }

    FxService(String baseUrl, String userAgent, long ttlSeconds, long timeoutMs, LongSupplier now) {
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory();
        rf.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.client = RestClient.builder()
                .requestFactory(rf)
                .baseUrl(baseUrl)
                .defaultHeader("User-Agent", userAgent)
                .build();
        // Currency-pair keyspace is small (ISO-4217 combinations); 1024 comfortably covers it.
        this.cache = new TtlCache<>(ttlSeconds * 1000L, 1024, now);
    }

    public FxRate rate(String from, String to) {
        String f = from == null ? "" : from.toUpperCase();
        String t = to == null ? "" : to.toUpperCase();
        if (f.equals(t)) return new FxRate(f, t, BigDecimal.ONE);
        if (!f.matches("[A-Z]{3}") || !t.matches("[A-Z]{3}"))
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "invalid currency code: " + f + "/" + t, null);
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
        try {
            return new FxRate(f, t, new BigDecimal(px.asString()));
        } catch (NumberFormatException e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "bad rate value for " + pair, e);
        }
    }
}
