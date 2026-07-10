package de.visterion.agora.data;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * TwelveData fallback market-data provider (quote + ohlc). Skips itself (throws UNAVAILABLE) when no
 * API key is configured, so the fallback chain moves on. Ports Dracul's TwelveDataMarketDataAdapter
 * to Agora's neutral DTOs.
 */
@Component
@Order(10)
public class TwelveDataMarketDataProvider implements MarketDataProvider {

    private final RestClient client;
    private final String key;

    /**
     * Constructor bound by Spring via {@code @Value}. Applies the configurable per-request read
     * timeout ({@code agora.data.provider-timeout-ms}) so a slow TwelveData call fails fast into the
     * next provider instead of stalling the chain.
     */
    @Autowired
    public TwelveDataMarketDataProvider(
            @Value("${agora.data.twelvedata.base-url}") String baseUrl,
            @Value("${agora.data.twelvedata.key}") String key,
            @Value("${agora.data.provider-timeout-ms:4000}") long timeoutMs) {
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory();
        rf.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(rf)
                .build();
        this.key = key;
    }

    /** Test constructor: explicit base-url + key, default timeout. */
    TwelveDataMarketDataProvider(String baseUrl, String key) {
        this(baseUrl, key, 4000L);
    }

    @Override
    public String name() {
        return "twelvedata";
    }

    @Override
    public Quote quote(String symbol) {
        if (key == null || key.isBlank()) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "twelvedata: no api key", null);
        }
        JsonNode node;
        try {
            node = client.get()
                    .uri(uri -> uri.path("/quote")
                            .queryParam("symbol", symbol)
                            .queryParam("apikey", key)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "TwelveData returned HTTP " + e.getStatusCode(), e);
        } catch (Exception e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "TwelveData unreachable: " + e.getMessage(), e);
        }
        if (node == null) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "TwelveData returned empty body for " + symbol, null);
        }
        if ("error".equals(node.path("status").asString(""))
                || node.path("close").isMissingNode()) {
            throw new MarketDataException(MarketDataException.Kind.NOT_FOUND,
                    "Symbol " + symbol + " not found at TwelveData", null);
        }
        return new Quote(symbol, bd(node, "close"), bd(node, "percent_change"),
                node.path("currency").asString("USD"));
    }

    @Override
    public List<OhlcBar> ohlc(String symbol, int days) {
        if (key == null || key.isBlank()) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "twelvedata: no api key", null);
        }
        JsonNode ts;
        try {
            ts = client.get()
                    .uri(uri -> uri.path("/time_series")
                            .queryParam("symbol", symbol)
                            .queryParam("interval", "1day")
                            .queryParam("outputsize", days)
                            .queryParam("apikey", key)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "TwelveData history returned HTTP " + e.getStatusCode(), e);
        } catch (Exception e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "TwelveData history unreachable: " + e.getMessage(), e);
        }
        if (ts == null) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "TwelveData history returned empty body for " + symbol, null);
        }
        if ("error".equals(ts.path("status").asString(""))) {
            throw new MarketDataException(MarketDataException.Kind.NOT_FOUND,
                    "Symbol " + symbol + " not found at TwelveData", null);
        }
        List<OhlcBar> out = new ArrayList<>();
        JsonNode values = ts.path("values");
        if (values.isArray()) {
            for (JsonNode v : values) {                       // API is newest-first
                String dt = v.path("datetime").asString("");
                BigDecimal o = parseBd(v.path("open")), h = parseBd(v.path("high")),
                           l = parseBd(v.path("low")), c = parseBd(v.path("close"));
                if (dt.length() < 10 || o == null || h == null || l == null || c == null) continue;
                LocalDate date;
                try { date = LocalDate.parse(dt.substring(0, 10)); }
                catch (Exception e) { continue; }
                out.add(new OhlcBar(date, o, h, l, c, v.path("volume").asLong(0)));
            }
            Collections.reverse(out);                         // -> oldest-first
        }
        // Empty history means "not served here" (e.g. plan-restricted exchange): throw
        // NOT_FOUND so MarketDataService falls through instead of caching an empty success.
        if (out.isEmpty()) {
            throw new MarketDataException(MarketDataException.Kind.NOT_FOUND,
                    "Symbol " + symbol + " has no bars at TwelveData", null);
        }
        return out;
    }

    private static BigDecimal parseBd(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return null;
        try { return new BigDecimal(n.asString("")); } catch (NumberFormatException e) { return null; }
    }

    private static BigDecimal bd(JsonNode node, String field) {
        String v = node.path(field).asString("0");
        if (v == null || v.isBlank()) return BigDecimal.ZERO;
        try { return new BigDecimal(v); } catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }
}
