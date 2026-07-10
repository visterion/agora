package de.visterion.agora.fetch.finnhub;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.ProviderErrors;
import de.visterion.agora.data.TtlCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/** Earnings surprises (actual/estimate/surprise) via Finnhub, cached (fundamentals TTL). */
@Component
public class EarningsEstimatesService {

    private static final Logger log = LoggerFactory.getLogger(EarningsEstimatesService.class);

    private final FinnhubClient client;
    private final TtlCache<String, List<EarningsEstimate>> cache;

    @Autowired
    public EarningsEstimatesService(FinnhubClient client,
            @Value("${agora.data.cache.ttl.fundamentals-seconds:21600}") long ttlSeconds) {
        this(client, ttlSeconds, System::currentTimeMillis);
    }

    EarningsEstimatesService(FinnhubClient client, long ttlSeconds, LongSupplier now) {
        this.client = client;
        this.cache = new TtlCache<>(ttlSeconds * 1000L, 2048, now);
    }

    public List<EarningsEstimate> earnings(String symbol) {
        if (!client.configured())
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "finnhub: no api key", null);
        return cache.get("earn:" + symbol, () -> fetch(symbol));
    }

    private List<EarningsEstimate> fetch(String symbol) {
        JsonNode arr;
        try {
            arr = client.http().get()
                    .uri(uri -> uri.path("/stock/earnings")
                            .queryParam("symbol", symbol)
                            .build())
                    .header(FinnhubClient.TOKEN_HEADER, client.token())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.warn("finnhub earnings-estimates request failed for {}", symbol, e);
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    ProviderErrors.categorize("finnhub earnings-estimates", e), e);
        }
        if (arr == null || !arr.isArray())
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "empty earnings body", null);
        List<EarningsEstimate> out = new ArrayList<>();
        for (JsonNode n : arr) {
            out.add(new EarningsEstimate(
                    n.path("period").asString(""),
                    bd(n.path("actual")), bd(n.path("estimate")),
                    bd(n.path("surprise")), bd(n.path("surprisePercent"))));
        }
        return out;
    }

    private static BigDecimal bd(JsonNode p) {
        return (p == null || p.isMissingNode() || p.isNull()) ? null : p.decimalValue();
    }
}
