package de.visterion.agora.fetch.finnhub;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.TtlCache;
import de.visterion.agora.fetch.split.SplitEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/** Stock splits via Finnhub, cached (fundamentals TTL). Empty list is a valid result. */
@Component
public class SplitService {

    private final FinnhubClient client;
    private final TtlCache<String, List<SplitEvent>> cache;

    @Autowired
    public SplitService(FinnhubClient client,
            @Value("${agora.data.cache.ttl.splits-seconds:21600}") long ttlSeconds) {
        this(client, ttlSeconds, System::currentTimeMillis);
    }

    SplitService(FinnhubClient client, long ttlSeconds, LongSupplier now) {
        this.client = client;
        this.cache = new TtlCache<>(ttlSeconds * 1000L, now);
    }

    public List<SplitEvent> splits(String symbol) {
        if (!client.configured())
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "finnhub: no api key", null);
        return cache.get("split:" + symbol, () -> fetch(symbol));
    }

    private List<SplitEvent> fetch(String symbol) {
        JsonNode arr;
        try {
            arr = client.http().get()
                    .uri(uri -> uri.path("/stock/split")
                            .queryParam("symbol", symbol)
                            .queryParam("from", "1990-01-01")
                            .queryParam("to", LocalDate.now().toString())
                            .queryParam("token", client.token())
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "finnhub split unreachable: " + e.getMessage(), e);
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
                    continue; // skip malformed entry, keep well-formed ones
                }
            }
        }
        return out;
    }
}
