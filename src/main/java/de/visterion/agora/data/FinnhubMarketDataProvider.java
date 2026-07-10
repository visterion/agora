package de.visterion.agora.data;

import de.visterion.agora.fetch.finnhub.FinnhubClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;

/**
 * Quote-only Finnhub fallback market-data provider. {@code ohlc} always throws UNAVAILABLE so the
 * fallback chain serves history from Yahoo/TwelveData (Finnhub's free tier has no cheap history).
 * Skips itself (UNAVAILABLE) when no API key is configured. Ports Dracul's FinnhubMarketDataAdapter
 * to Agora's neutral DTOs.
 */
@Component
@Order(20)
public class FinnhubMarketDataProvider implements MarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(FinnhubMarketDataProvider.class);

    private final RestClient client;
    private final String key;

    /**
     * Constructor bound by Spring via {@code @Value}. Applies the configurable per-request read
     * timeout ({@code agora.data.provider-timeout-ms}) so a slow Finnhub call fails fast into the
     * next provider instead of stalling the chain.
     */
    @Autowired
    public FinnhubMarketDataProvider(
            @Value("${agora.data.finnhub.base-url}") String baseUrl,
            @Value("${agora.data.finnhub.key}") String key,
            @Value("${agora.data.provider-timeout-ms:4000}") long timeoutMs) {
        JdkClientHttpRequestFactory rf = DataHttp.requestFactory(timeoutMs);
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(rf)
                .build();
        this.key = key;
    }

    /** Test constructor: explicit base-url + key, default timeout. */
    FinnhubMarketDataProvider(String baseUrl, String key) {
        this(baseUrl, key, 4000L);
    }

    @Override
    public String name() {
        return "finnhub";
    }

    @Override
    public Quote quote(String symbol) {
        if (key == null || key.isBlank()) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "finnhub: no api key", null);
        }
        JsonNode node;
        try {
            node = client.get()
                    .uri(uri -> uri.path("/quote")
                            .queryParam("symbol", symbol)
                            .build())
                    .header(FinnhubClient.TOKEN_HEADER, key)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "Finnhub returned HTTP " + e.getStatusCode(), e);
        } catch (Exception e) {
            log.warn("finnhub quote request failed for {}", symbol, e);
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    ProviderErrors.categorize("finnhub", e), e);
        }
        if (node == null) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "Finnhub returned empty body for " + symbol, null);
        }
        BigDecimal price = bd(node, "c");
        if (price.signum() == 0) {   // 0 = unknown symbol / no data
            throw new MarketDataException(MarketDataException.Kind.NOT_FOUND,
                    "Symbol " + symbol + " not found at Finnhub", null);
        }
        // Finnhub /quote has no currency field.
        return new Quote(symbol, price, bd(node, "dp"), "USD");
    }

    @Override
    public List<OhlcBar> ohlc(String symbol, int days) {
        throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                "finnhub: quote-only (no ohlc)", null);
    }

    private static BigDecimal bd(JsonNode node, String field) {
        JsonNode f = node.path(field);
        if (f.isMissingNode() || f.isNull()) return BigDecimal.ZERO;
        String v = f.asString("0");
        if (v.isBlank()) return BigDecimal.ZERO;
        try { return new BigDecimal(v); } catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }
}
