package de.visterion.agora.fetch.split;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.alpaca.AlpacaDataClient;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Stock splits via Alpaca /v1beta1/corporate-actions (forward + reverse). Empty list is valid. */
@Component
@Order(10)
public class AlpacaSplitProvider implements SplitProvider {

    private final AlpacaDataClient client;

    public AlpacaSplitProvider(AlpacaDataClient client) { this.client = client; }

    @Override public String name() { return "alpaca"; }

    @Override
    public List<SplitEvent> splits(String symbol) {
        if (!client.configured())
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "alpaca: no api key", null);
        JsonNode root;
        try {
            root = client.http().get()
                    .uri(uri -> uri.path("/v1beta1/corporate-actions")
                            .queryParam("symbols", symbol)
                            .queryParam("types", "forward_split,reverse_split")
                            .queryParam("start", "1990-01-01")
                            .queryParam("end", LocalDate.now().toString())
                            .queryParam("limit", "1000")
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "alpaca corporate-actions unreachable: " + e.getMessage(), e);
        }
        List<SplitEvent> out = new ArrayList<>();
        if (root != null) {
            JsonNode ca = root.path("corporate_actions");
            addSplits(out, ca.path("forward_splits"));
            addSplits(out, ca.path("reverse_splits"));
        }
        return out;
    }

    private static void addSplits(List<SplitEvent> out, JsonNode arr) {
        if (arr == null || !arr.isArray()) return;
        for (JsonNode n : arr) {
            try {
                out.add(new SplitEvent(
                        LocalDate.parse(n.path("ex_date").asString()),
                        n.path("old_rate").decimalValue(),
                        n.path("new_rate").decimalValue()));
            } catch (RuntimeException e) {
                // skip malformed entry
            }
        }
    }
}
