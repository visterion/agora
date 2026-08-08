package de.visterion.agora.fetch.split;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.ProviderErrors;
import de.visterion.agora.fetch.finnhub.FinnhubClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Stock splits via Finnhub /stock/split. Empty list is a valid result. */
@Component
@Order(20)
public class FinnhubSplitProvider implements SplitProvider {

    private static final Logger log = LoggerFactory.getLogger(FinnhubSplitProvider.class);

    private final FinnhubClient client;
    // Default OFF: the account's Finnhub plan does not include /stock/split. Every call in the
    // 2026-08-08 sweep got 403 Forbidden {"error":"You don't have access to this resource."} —
    // confirmed for VICI (a US S&P 500 symbol, so this is not a non-US gap), BMW.DE and 0005.HK.
    // Alpaca has been the primary split provider since 2026-07-07 for exactly this reason; do
    // not flip this back on without first upgrading the Finnhub plan.
    private final boolean splitsEnabled;

    @Autowired
    public FinnhubSplitProvider(FinnhubClient client,
            @Value("${agora.data.finnhub.splits-enabled:false}") boolean splitsEnabled) {
        this.client = client;
        this.splitsEnabled = splitsEnabled;
    }

    // Test/legacy constructor: defaults to enabled so callers that construct this directly (e.g.
    // existing provider-level tests written before the switch existed) keep exercising the HTTP
    // path unless they opt out explicitly via the two-arg constructor.
    public FinnhubSplitProvider(FinnhubClient client) { this(client, true); }

    @Override public String name() { return "finnhub"; }

    @Override
    public List<SplitEvent> splits(String symbol) {
        if (!splitsEnabled || !client.configured())
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "finnhub: no api key", null);
        JsonNode arr;
        try {
            arr = client.http().get()
                    .uri(uri -> uri.path("/stock/split")
                            .queryParam("symbol", symbol)
                            .queryParam("from", "1990-01-01")
                            .queryParam("to", LocalDate.now().toString())
                            .build())
                    .header(FinnhubClient.TOKEN_HEADER, client.token())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.warn("finnhub split request failed for {}", symbol, e);
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    ProviderErrors.categorize("finnhub split", e), e);
        }
        List<SplitEvent> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                try {
                    out.add(new SplitEvent(
                            LocalDate.parse(n.path("date").asString()),
                            n.path("fromFactor").decimalValue(),
                            n.path("toFactor").decimalValue()));
                } catch (RuntimeException e) {
                    // skip malformed entry
                }
            }
        }
        return out;
    }
}
