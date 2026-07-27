package de.visterion.agora.fetch.earnings;

import de.visterion.agora.data.DataHttp;
import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.ProviderErrors;
import de.visterion.agora.fetch.finnhub.FinnhubClient;
import de.visterion.agora.fetch.finnhub.FinnhubRateLimiter;
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

    private static final Logger log = LoggerFactory.getLogger(FinnhubEarningsProvider.class);

    private final RestClient client;
    private final String key;

    /**
     * Constructor bound by Spring via {@code @Value}. Uses {@code agora.fetch.earnings.attempt-timeout-ms}
     * (not the generic {@code agora.fetch.timeout-ms} the shared {@link FinnhubClient} uses) — this
     * provider is merged under {@code EarningsService}'s per-attempt budget. Registers the shared
     * {@link FinnhubRateLimiter} in {@link FinnhubRateLimiter.Mode#WAIT} mode: unlike the quote path
     * there is no alternative Finnhub-free fallback for this call, so exhaustion waits (bounded)
     * instead of failing immediately.
     */
    @Autowired
    public FinnhubEarningsProvider(
            @Value("${agora.data.finnhub.base-url}") String baseUrl,
            @Value("${agora.data.finnhub.key}") String key,
            @Value("${agora.fetch.earnings.attempt-timeout-ms:4000}") long timeoutMs,
            FinnhubRateLimiter rateLimiter) {
        this.client = DataHttp.clientBuilder(timeoutMs, rateLimiter.withMode(FinnhubRateLimiter.Mode.WAIT))
                .baseUrl(baseUrl)
                .build();
        this.key = key;
    }

    /** Test constructor: explicit timeout, no-op rate limiting. */
    public FinnhubEarningsProvider(String baseUrl, String key, long timeoutMs) {
        this(baseUrl, key, timeoutMs, new FinnhubRateLimiter(Integer.MAX_VALUE, 0L, System::currentTimeMillis));
    }

    /** Convenience constructor (default per-request timeout, no-op rate limiting); used by tests. */
    public FinnhubEarningsProvider(String baseUrl, String key) {
        this(baseUrl, key, 4_000L);
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
