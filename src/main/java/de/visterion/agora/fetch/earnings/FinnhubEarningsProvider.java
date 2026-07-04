package de.visterion.agora.fetch.earnings;

import de.visterion.agora.data.MarketDataException;
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

    private final RestClient client;
    private final String key;

    /**
     * Constructor bound by Spring via {@code @Value} and also invoked directly from WireMock tests
     * with an explicit base-url + key (both parameters are plain strings, so no separate test ctor
     * is needed).
     */
    @Autowired
    public FinnhubEarningsProvider(
            @Value("${agora.data.finnhub.base-url}") String baseUrl,
            @Value("${agora.data.finnhub.key}") String key) {
        this.client = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory())
                .baseUrl(baseUrl)
                .build();
        this.key = key;
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
                           .queryParam("to", to.toString())
                           .queryParam("token", key);
                        if (!marketWide) uri.queryParam("symbol", symbol);
                        return uri.build();
                    })
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "finnhub earnings HTTP " + e.getStatusCode(), e);
        } catch (Exception e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "finnhub earnings unreachable: " + e.getMessage(), e);
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
            String sym = row.path("symbol").asString(symbol).toUpperCase();
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
