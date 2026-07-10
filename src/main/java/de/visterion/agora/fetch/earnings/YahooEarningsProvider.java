package de.visterion.agora.fetch.earnings;

import de.visterion.agora.data.DataHttp;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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

    /** Yahoo's calendar listings are US-centric; the event date is the exchange-local (Eastern) calendar day. */
    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("America/New_York");
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 10;

    private final RestClient client;

    @Autowired
    public YahooEarningsProvider(
            @Value("${agora.data.yahoo.base-url}") String baseUrl,
            @Value("${agora.data.yahoo.user-agent}") String userAgent,
            @Value("${agora.fetch.timeout-ms:15000}") long timeoutMs) {
        JdkClientHttpRequestFactory rf = DataHttp.requestFactory(timeoutMs);
        this.client = RestClient.builder()
                .requestFactory(rf)
                .baseUrl(baseUrl)
                .defaultHeader("User-Agent", userAgent)
                .build();
    }

    /** Convenience constructor (default per-request timeout); used by tests. */
    public YahooEarningsProvider(String baseUrl, String userAgent) {
        this(baseUrl, userAgent, 15_000L);
    }

    @Override
    public String name() { return "yahoo"; }

    @Override
    public List<EarningsEvent> earnings(String symbol, LocalDate from, LocalDate to) {
        boolean marketWide = symbol == null || symbol.isBlank();
        String want = marketWide ? "" : symbol.toUpperCase();
        List<EarningsEvent> out = new ArrayList<>();

        // Busy calendar days can exceed one page; paginate by offset until the requested symbol
        // is found, a short (last) page is returned, or the page cap is hit.
        for (int page = 0; page < MAX_PAGES; page++) {
            int offset = page * PAGE_SIZE;
            JsonNode body = fetchPage(from, to, offset);

            int rowCount = 0;
            boolean found = false;
            for (JsonNode row : body.path("rows")) {
                rowCount++;
                String ticker = row.path("ticker").asString("").toUpperCase();
                if (ticker.isEmpty()) continue;
                if (!marketWide && !ticker.equals(want)) continue;
                LocalDate date = eventDate(row);
                if (date == null) continue;
                out.add(new EarningsEvent(ticker, date,
                        dec(row, "epsestimate"), dec(row, "epsactual"), dec(row, "epssurprisepct"),
                        null, null));
                if (!marketWide) found = true;
            }
            if (found) break;
            if (rowCount < PAGE_SIZE) break;
        }
        return out;
    }

    private JsonNode fetchPage(LocalDate from, LocalDate to, int offset) {
        JsonNode body;
        try {
            body = client.get()
                    .uri(uri -> uri.path("/v1/finance/calendar/earnings")
                            .queryParam("startdt", from.toString())
                            .queryParam("enddt", to.toString())
                            .queryParam("offset", offset)
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
        return body;
    }

    /** Event date in the exchange-local (Eastern) calendar day, not the raw UTC calendar day. */
    private static LocalDate eventDate(JsonNode row) {
        String dt = row.path("startdatetime").asString("");
        if (dt.isEmpty()) return null;
        try {
            return Instant.parse(dt).atZone(EXCHANGE_ZONE).toLocalDate();
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal dec(JsonNode row, String field) {
        JsonNode n = row.path(field);
        if (n.isMissingNode() || n.isNull()) return null;
        String s = n.asString("");
        if (s.isEmpty()) return null;
        try { return new BigDecimal(s); } catch (NumberFormatException e) { return null; }
    }
}
