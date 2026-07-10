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
                           @Value("${agora.data.cache.ttl.prices-seconds:120}") long ttlSeconds,
                           @Value("${agora.data.provider-timeout-ms:4000}") long timeoutMs) {
        this(baseUrl, userAgent, interval, range, ttlSeconds, timeoutMs, System::currentTimeMillis);
    }

    IntradayService(String baseUrl, String userAgent, String interval, String range,
                    long ttlSeconds, long timeoutMs, LongSupplier now) {
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory();
        rf.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.client = RestClient.builder()
                .requestFactory(rf)
                .baseUrl(baseUrl)
                .defaultHeader("User-Agent", userAgent)
                .build();
        this.defaultInterval = interval;
        this.defaultRange = range;
        // Keyed by symbol+interval+range; quote/ohlc-scale cardinality.
        this.cache = new TtlCache<>(ttlSeconds * 1000L, 4096, now);
    }

    public List<IntradayBar> intraday(String symbol, String interval, String range) {
        if (symbol == null || symbol.isBlank())
            throw new MarketDataException(MarketDataException.Kind.NOT_FOUND, "symbol required", null);
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
            // 404 means the symbol doesn't exist at Yahoo — NOT_FOUND, not UNAVAILABLE (lows).
            var kind = e.getStatusCode().value() == 404
                    ? MarketDataException.Kind.NOT_FOUND
                    : MarketDataException.Kind.UNAVAILABLE;
            throw new MarketDataException(kind, "Yahoo intraday HTTP " + e.getStatusCode(), e);
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
                // H2: any missing O/H/L/C makes the bar unusable — skip it instead of
                // fabricating a ZERO that would corrupt low/high-based calculations.
                if (isMissing(opens, i) || isMissing(highs, i) || isMissing(lows, i) || isMissing(closes, i)) {
                    continue;
                }
                out.add(new IntradayBar(Instant.ofEpochSecond(ts.get(i).asLong()),
                        bd(opens.path(i)), bd(highs.path(i)), bd(lows.path(i)), bd(closes.path(i)),
                        vols.path(i).asLong(0)));
            }
        }
        // Never cache an empty result as success: an all-null/empty candle set means
        // "nothing to serve" (unknown symbol variant, dead session) — signal NOT_FOUND.
        if (out.isEmpty()) {
            throw new MarketDataException(MarketDataException.Kind.NOT_FOUND,
                    "no intraday bars for " + symbol, null);
        }
        return out;
    }

    private static BigDecimal bd(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return BigDecimal.ZERO;
        try { return new BigDecimal(n.asString("0")); } catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    private static boolean isMissing(JsonNode array, int i) {
        JsonNode n = array.path(i);
        return n.isNull() || n.isMissingNode();
    }
}
