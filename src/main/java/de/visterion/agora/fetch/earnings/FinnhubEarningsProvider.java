package de.visterion.agora.fetch.earnings;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.ProviderErrors;
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
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Finnhub {@code /calendar/earnings} earnings provider (primary source). Throws
 * MarketDataException(UNAVAILABLE) — including on a blank API key — so the fallback chain yields to
 * Yahoo. Ports Dracul's FinnhubEarningsAdapter to Agora's neutral {@link EarningsEvent}.
 */
@Component
@Order(0)
public class FinnhubEarningsProvider implements EarningsProvider {

    private static final Logger log = LoggerFactory.getLogger(FinnhubEarningsProvider.class);

    private final RestClient client;
    private final String key;

    /**
     * Constructor bound by Spring via {@code @Value} and also invoked directly from WireMock tests
     * with an explicit base-url + key + timeout (all parameters are plain values, so no separate
     * test ctor is needed).
     */
    @Autowired
    public FinnhubEarningsProvider(
            @Value("${agora.data.finnhub.base-url}") String baseUrl,
            @Value("${agora.data.finnhub.key}") String key,
            @Value("${agora.fetch.timeout-ms:15000}") long timeoutMs) {
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory();
        rf.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.client = RestClient.builder()
                .requestFactory(rf)
                .baseUrl(baseUrl)
                .build();
        this.key = key;
    }

    /** Convenience constructor (default per-request timeout); used by tests. */
    public FinnhubEarningsProvider(String baseUrl, String key) {
        this(baseUrl, key, 15_000L);
    }

    @Override
    public String name() { return "finnhub"; }

    @Override
    public List<EarningsEvent> earnings(String symbol, LocalDate from, LocalDate to) {
        if (key == null || key.isBlank())
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "finnhub: no api key", null);
        boolean marketWide = symbol == null || symbol.isBlank();
        JsonNode body;
        try {
            body = client.get()
                    .uri(uri -> {
                        uri.path("/calendar/earnings")
                           .queryParam("from", from.toString())
                           .queryParam("to", to.toString());
                        if (!marketWide) uri.queryParam("symbol", symbol);
                        return uri.build();
                    })
                    .header(FinnhubClient.TOKEN_HEADER, key)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "finnhub earnings HTTP " + e.getStatusCode(), e);
        } catch (Exception e) {
            log.warn("finnhub earnings request failed for {}", symbol, e);
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    ProviderErrors.categorize("finnhub earnings", e), e);
        }
        if (body == null)
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "empty earnings body", null);

        List<EarningsEvent> out = new ArrayList<>();
        for (JsonNode row : body.path("earningsCalendar")) {
            LocalDate date;
            try {
                date = LocalDate.parse(row.path("date").asString(""));
            } catch (Exception e) {
                continue; // skip rows with a missing/malformed date
            }
            BigDecimal actual = dec(row, "epsActual");
            BigDecimal estimate = dec(row, "epsEstimate");
            BigDecimal surprisePct = null;
            if (actual != null && estimate != null && estimate.signum() != 0) {
                surprisePct = actual.subtract(estimate)
                        .divide(estimate.abs(), 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
            }
            String sym = row.path("symbol").asString(symbol == null ? "" : symbol).toUpperCase();
            if (sym.isBlank()) continue;
            out.add(new EarningsEvent(sym, date, estimate, actual, surprisePct,
                    dec(row, "revenueEstimate"), dec(row, "revenueActual")));
        }
        return out;
    }

    private static BigDecimal dec(JsonNode row, String field) {
        JsonNode n = row.path(field);
        if (n.isMissingNode() || n.isNull()) return null;
        try { return new BigDecimal(n.asString("")); } catch (NumberFormatException e) { return null; }
    }
}
