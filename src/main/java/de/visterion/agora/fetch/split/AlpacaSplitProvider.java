package de.visterion.agora.fetch.split;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.alpaca.AlpacaDataClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(AlpacaSplitProvider.class);

    // Guards against a misbehaving upstream returning a repeating/cyclic next_page_token,
    // which would otherwise spin this loop forever (mirrors the Saxo __next page cap).
    private static final int MAX_PAGES = 50;

    private final AlpacaDataClient client;

    public AlpacaSplitProvider(AlpacaDataClient client) { this.client = client; }

    @Override public String name() { return "alpaca"; }

    @Override
    public List<SplitEvent> splits(String symbol) {
        if (!client.configured())
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "alpaca: no api key", null);
        List<SplitEvent> out = new ArrayList<>();
        String pageToken = null;
        int pages = 0;
        do {
            pages++;
            JsonNode root;
            String currentToken = pageToken;
            try {
                root = client.http().get()
                        .uri(uri -> {
                            var builder = uri.path("/v1beta1/corporate-actions")
                                    .queryParam("symbols", symbol)
                                    .queryParam("types", "forward_split,reverse_split")
                                    .queryParam("start", "1990-01-01")
                                    .queryParam("end", LocalDate.now().toString())
                                    .queryParam("limit", "1000");
                            if (currentToken != null) builder.queryParam("page_token", currentToken);
                            return builder.build();
                        })
                        .retrieve()
                        .body(JsonNode.class);
            } catch (Exception e) {
                throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                        "alpaca corporate-actions unreachable: " + e.getMessage(), e);
            }
            if (root == null) break;
            JsonNode ca = root.path("corporate_actions");
            addSplits(out, ca.path("forward_splits"));
            addSplits(out, ca.path("reverse_splits"));
            JsonNode next = root.path("next_page_token");
            pageToken = (next.isMissingNode() || next.isNull()) ? null : next.asString(null);
            if (pageToken != null && pages >= MAX_PAGES) {
                log.debug("alpaca corporate-actions pagination capped at {} pages for symbol={}", MAX_PAGES, symbol);
                break;
            }
        } while (pageToken != null);
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
