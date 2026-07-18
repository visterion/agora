package de.visterion.agora.data;

import de.visterion.agora.fetch.finnhub.FinnhubClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

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
    private final Set<String> nonUsSuffixes;

    /**
     * Constructor bound by Spring via {@code @Value}. Applies the configurable per-request read
     * timeout ({@code agora.data.provider-timeout-ms}) so a slow Finnhub call fails fast into the
     * next provider instead of stalling the chain. Reuses the same
     * {@code agora.fundamentals.non-us-suffixes} property fundamentals routing reads, so the two
     * never drift.
     */
    @Autowired
    public FinnhubMarketDataProvider(
            @Value("${agora.data.finnhub.base-url}") String baseUrl,
            @Value("${agora.data.finnhub.key}") String key,
            @Value("${agora.data.provider-timeout-ms:4000}") long timeoutMs,
            @Value("${agora.fundamentals.non-us-suffixes:" + NonUsSuffixes.DEFAULT_CSV + "}") String nonUsSuffixesCsv) {
        this.client = DataHttp.clientBuilder(timeoutMs)
                .baseUrl(baseUrl)
                .build();
        this.key = key;
        this.nonUsSuffixes = NonUsSuffixes.parse(nonUsSuffixesCsv);
    }

    /** Test constructor: explicit timeout, default non-US suffix set. */
    FinnhubMarketDataProvider(String baseUrl, String key, long timeoutMs) {
        this(baseUrl, key, timeoutMs, NonUsSuffixes.DEFAULT_CSV);
    }

    /** Test constructor: explicit base-url + key, default timeout + default non-US suffix set. */
    FinnhubMarketDataProvider(String baseUrl, String key) {
        this(baseUrl, key, 4000L);
    }

    @Override
    public String name() {
        return "finnhub";
    }

    /** Quote-only US fallback feed: skip non-US instruments so the fallback chain reaches
     *  Saxo/Yahoo without a wasted 4xx round-trip. */
    @Override
    public boolean canServe(Instrument inst) {
        return !NonUsSuffixes.isNonUs(inst, nonUsSuffixes);
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
        // Finnhub /quote has no currency field. USD is a safe default only for plain (non-suffixed)
        // symbols; a Yahoo-style exchange suffix (SAP.DE, AIR.PA, ...) means a non-US listing whose
        // real currency we don't know here — report it as unknown (null) rather than silently wrong.
        String currency = hasYahooSuffix(symbol) ? null : "USD";
        return new Quote(symbol, price, bd(node, "dp"), currency);
    }

    @Override
    public List<OhlcBar> ohlc(String symbol, int days) {
        throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                "finnhub: quote-only (no ohlc)", null);
    }

    /** True for Yahoo-style suffixed symbols such as "SAP.DE" or "AIR.PA" (a dot not at the start
     *  or end of the string). */
    private static boolean hasYahooSuffix(String symbol) {
        int dot = symbol.lastIndexOf('.');
        return dot > 0 && dot < symbol.length() - 1;
    }

    private static BigDecimal bd(JsonNode node, String field) {
        JsonNode f = node.path(field);
        if (f.isMissingNode() || f.isNull()) return BigDecimal.ZERO;
        String v = f.asString("0");
        if (v.isBlank()) return BigDecimal.ZERO;
        try { return new BigDecimal(v); } catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }
}
