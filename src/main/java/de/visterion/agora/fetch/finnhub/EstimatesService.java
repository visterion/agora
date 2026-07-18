package de.visterion.agora.fetch.finnhub;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.NonUsSuffixes;
import de.visterion.agora.data.ProviderErrors;
import de.visterion.agora.data.TtlCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;

/** Analyst recommendation trend for a symbol via Finnhub, cached per-family (fundamentals TTL). */
@Component
public class EstimatesService {

    private static final Logger log = LoggerFactory.getLogger(EstimatesService.class);

    private final FinnhubClient client;
    private final TtlCache<String, List<Recommendation>> cache;
    private final Set<String> nonUsSuffixes;
    private final YahooCompanyDataSource yahoo;
    private final TtlCache<String, List<Recommendation>> yahooCache;

    @Autowired
    public EstimatesService(FinnhubClient client,
                            @Value("${agora.data.cache.ttl.fundamentals-seconds:21600}") long ttlSeconds,
                            @Value("${agora.fundamentals.non-us-suffixes:DE,MI,TO,L,T,HK,PA,AS,SW,AX,ST,CO,OL,HE,MC,BR,LS,VI,IR,NZ}") String nonUsSuffixesCsv,
                            @Value("${agora.data.cache.ttl.recommendation-seconds:86400}") long yahooTtlSeconds,
                            YahooCompanyDataSource yahoo) {
        this(client, ttlSeconds, System::currentTimeMillis, NonUsSuffixes.parse(nonUsSuffixesCsv), yahooTtlSeconds, yahoo);
    }

    EstimatesService(FinnhubClient client, long ttlSeconds, LongSupplier now, long yahooTtlSeconds, YahooCompanyDataSource yahoo) {
        this(client, ttlSeconds, now, NonUsSuffixes.DEFAULT, yahooTtlSeconds, yahoo);
    }

    EstimatesService(FinnhubClient client, long ttlSeconds, LongSupplier now, Set<String> nonUsSuffixes,
                      long yahooTtlSeconds, YahooCompanyDataSource yahoo) {
        this.client = client;
        this.cache = new TtlCache<>(ttlSeconds * 1000L, 2048, now);
        this.nonUsSuffixes = nonUsSuffixes;
        this.yahoo = yahoo;
        this.yahooCache = new TtlCache<>(yahooTtlSeconds * 1000L, 2048, now);
    }

    public List<Recommendation> recommendations(String symbol) {
        if (NonUsSuffixes.isNonUs(symbol, nonUsSuffixes)) {
            try {
                return yahooCache.get("rec:" + symbol, () -> yahoo.recommendations(symbol));
            } catch (MarketDataException e) {
                return List.of();
            }
        }
        if (!client.configured())
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "finnhub: no api key", null);
        return cache.get("rec:" + symbol, () -> fetch(symbol));
    }

    private List<Recommendation> fetch(String symbol) {
        JsonNode arr;
        try {
            arr = client.http().get()
                    .uri(uri -> uri.path("/stock/recommendation")
                            .queryParam("symbol", symbol)
                            .build())
                    .header(FinnhubClient.TOKEN_HEADER, client.token())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.warn("finnhub recommendation request failed for {}", symbol, e);
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    ProviderErrors.categorize("finnhub recommendation", e), e);
        }
        if (arr == null || !arr.isArray())
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "empty recommendation body", null);
        List<Recommendation> out = new ArrayList<>();
        for (JsonNode n : arr) {
            out.add(new Recommendation(
                    n.path("period").asString(""),
                    n.path("strongBuy").asInt(0),
                    n.path("buy").asInt(0),
                    n.path("hold").asInt(0),
                    n.path("sell").asInt(0),
                    n.path("strongSell").asInt(0)));
        }
        return out;
    }
}
