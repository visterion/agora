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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Yahoo earnings-calendar fallback provider. The endpoint is market-wide, so results are filtered by
 * symbol client-side. Any fetch failure throws MarketDataException(UNAVAILABLE); an empty rows array
 * yields an empty list (not an error). Ports Dracul's YahooEarningsAdapter to Agora's neutral DTOs.
 */
@Component
@Order(10)
public class YahooEarningsProvider implements EarningsProvider {

    private final RestClient client;

    @Autowired
    public YahooEarningsProvider(
            @Value("${agora.data.yahoo.base-url}") String baseUrl,
            @Value("${agora.data.yahoo.user-agent}") String userAgent) {
        this.client = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory())
                .baseUrl(baseUrl)
                .defaultHeader("User-Agent", userAgent)
                .build();
    }

    @Override
    public String name() { return "yahoo"; }

    @Override
    public List<EarningsEvent> earnings(String symbol, LocalDate from, LocalDate to) {
        JsonNode body;
        try {
            body = client.get()
                    .uri(uri -> uri.path("/v1/finance/calendar/earnings")
                            .queryParam("startdt", from.toString())
                            .queryParam("enddt", to.toString())
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "yahoo earnings HTTP " + e.getStatusCode(), e);
        } catch (Exception e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "yahoo earnings unreachable: " + e.getMessage(), e);
        }
        if (body == null)
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "empty earnings body", null);

        String want = symbol == null ? "" : symbol.toUpperCase();
        List<EarningsEvent> out = new ArrayList<>();
        for (JsonNode row : body.path("rows")) {
            String ticker = row.path("ticker").asString("").toUpperCase();
            if (ticker.isEmpty() || !ticker.equals(want)) continue;
            String dt = row.path("startdatetime").asString("");
            if (dt.length() < 10) continue;
            LocalDate date;
            try {
                date = LocalDate.parse(dt.substring(0, 10));
            } catch (Exception e) {
                continue;
            }
            out.add(new EarningsEvent(ticker, date,
                    dec(row, "epsestimate"), dec(row, "epsactual"), dec(row, "epssurprisepct"),
                    null, null));
        }
        return out;
    }

    private static BigDecimal dec(JsonNode row, String field) {
        JsonNode n = row.path(field);
        if (n.isMissingNode() || n.isNull()) return null;
        String s = n.asString("");
        if (s.isEmpty()) return null;
        try { return new BigDecimal(s); } catch (NumberFormatException e) { return null; }
    }
}
