package de.visterion.agora.data;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/** Yahoo intraday OHLCV candles at a configurable interval/range, with per-family TTL caching. */
@Component
public class IntradayService {

    private final RestClient client;
    private final String defaultInterval;
    private final String defaultRange;
    private final TtlCache<String, List<IntradayBar>> cache;

    @Autowired
    public IntradayService(@Value("${agora.data.yahoo.base-url}") String baseUrl,
                           @Value("${agora.data.yahoo.user-agent}") String userAgent,
                           @Value("${agora.data.intraday.interval:5m}") String interval,
                           @Value("${agora.data.intraday.range:1d}") String range,
                           @Value("${agora.data.cache.ttl.prices-seconds:120}") long ttlSeconds) {
        this(baseUrl, userAgent, interval, range, ttlSeconds, System::currentTimeMillis);
    }

    IntradayService(String baseUrl, String userAgent, String interval, String range,
                    long ttlSeconds, LongSupplier now) {
        this.client = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory())
                .baseUrl(baseUrl)
                .defaultHeader("User-Agent", userAgent)
                .build();
        this.defaultInterval = interval;
        this.defaultRange = range;
        this.cache = new TtlCache<>(ttlSeconds * 1000L, now);
    }

    public List<IntradayBar> intraday(String symbol, String interval, String range) {
        String iv = (interval == null || interval.isBlank()) ? defaultInterval : interval;
        String rg = (range == null || range.isBlank()) ? defaultRange : range;
        return cache.get("intraday:" + symbol + ":" + iv + ":" + rg, () -> fetch(symbol, iv, rg));
    }

    private List<IntradayBar> fetch(String symbol, String iv, String rg) {
        JsonNode body;
        try {
            body = client.get()
                    .uri(uri -> uri.path("/v8/finance/chart/{symbol}")
                            .queryParam("range", rg).queryParam("interval", iv).build(symbol))
                    .retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "Yahoo intraday HTTP " + e.getStatusCode(), e);
        } catch (Exception e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "Yahoo intraday unreachable: " + e.getMessage(), e);
        }
        if (body == null) throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "empty body", null);
        JsonNode result = body.path("chart").path("result");
        if (!result.isArray() || result.isEmpty() || result.get(0).isNull())
            throw new MarketDataException(MarketDataException.Kind.NOT_FOUND, "symbol " + symbol + " not found", null);
        JsonNode r0 = result.get(0);
        JsonNode ts = r0.path("timestamp");
        JsonNode q = r0.path("indicators").path("quote").path(0);
        JsonNode opens = q.path("open"), highs = q.path("high"), lows = q.path("low"),
                 closes = q.path("close"), vols = q.path("volume");
        List<IntradayBar> out = new ArrayList<>();
        if (ts.isArray()) {
            for (int i = 0; i < ts.size(); i++) {
                JsonNode c = closes.path(i);
                if (c.isNull() || c.isMissingNode()) continue;
                out.add(new IntradayBar(Instant.ofEpochSecond(ts.get(i).asLong()),
                        bd(opens.path(i)), bd(highs.path(i)), bd(lows.path(i)), bd(c),
                        vols.path(i).asLong(0)));
            }
        }
        return out;
    }

    private static BigDecimal bd(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return BigDecimal.ZERO;
        try { return new BigDecimal(n.asString("0")); } catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }
}
