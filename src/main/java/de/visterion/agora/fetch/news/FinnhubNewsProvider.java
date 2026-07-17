package de.visterion.agora.fetch.news;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.NonUsSuffixes;
import de.visterion.agora.data.ProviderErrors;
import de.visterion.agora.data.TtlCache;
import de.visterion.agora.fetch.finnhub.FinnhubClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;

/** Company news headlines via Finnhub, cached per-family (news TTL). sourceType is always "news". */
@Component
public class FinnhubNewsProvider implements NewsProvider {

    private static final Logger log = LoggerFactory.getLogger(FinnhubNewsProvider.class);

    private final FinnhubClient client;
    private final TtlCache<String, List<NewsItem>> cache;
    private final Set<String> nonUsSuffixes;

    @Autowired
    public FinnhubNewsProvider(FinnhubClient client,
                               @Value("${agora.data.cache.ttl.news-seconds:900}") long ttlSeconds,
                               @Value("${agora.fundamentals.non-us-suffixes:DE,MI,TO,L,T,HK,PA,AS,SW,AX,ST,CO,OL,HE,MC,BR,LS,VI,IR,NZ}") String nonUsSuffixesCsv) {
        this(client, ttlSeconds, System::currentTimeMillis, NonUsSuffixes.parse(nonUsSuffixesCsv));
    }

    FinnhubNewsProvider(FinnhubClient client, long ttlSeconds, LongSupplier now) {
        this(client, ttlSeconds, now, NonUsSuffixes.DEFAULT);
    }

    FinnhubNewsProvider(FinnhubClient client, long ttlSeconds, LongSupplier now, Set<String> nonUsSuffixes) {
        this.client = client;
        // Keyed by symbol+date-range, so cardinality grows with distinct windows queried.
        this.cache = new TtlCache<>(ttlSeconds * 1000L, 2048, now);
        this.nonUsSuffixes = nonUsSuffixes;
    }

    @Override
    public String id() { return "finnhub"; }

    @Override
    public boolean configured() { return client.configured(); }

    @Override
    public List<NewsItem> companyNews(String symbol, LocalDate from, LocalDate to) {
        if (NonUsSuffixes.isNonUs(symbol, nonUsSuffixes)) return List.of();
        if (!client.configured())
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "finnhub: no api key", null);
        return cache.get("news:" + symbol + ":" + from + ":" + to, () -> fetch(symbol, from, to));
    }

    private List<NewsItem> fetch(String symbol, LocalDate from, LocalDate to) {
        JsonNode arr;
        try {
            arr = client.http().get()
                    .uri(uri -> uri.path("/company-news")
                            .queryParam("symbol", symbol)
                            .queryParam("from", from.toString())
                            .queryParam("to", to.toString())
                            .build())
                    .header(FinnhubClient.TOKEN_HEADER, client.token())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.warn("finnhub company-news request failed for {}", symbol, e);
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    ProviderErrors.categorize("finnhub company-news", e), e);
        }
        if (arr == null || !arr.isArray())
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "empty news body", null);
        List<NewsItem> out = new ArrayList<>();
        for (JsonNode n : arr) {
            String headline = n.path("headline").asString("");
            if (headline.isBlank()) continue;
            out.add(new NewsItem(
                    headline,
                    n.path("summary").asString(""),
                    n.path("source").asString(""),
                    "news",
                    epochSeconds(n.path("datetime")),
                    n.path("url").asString("")));
        }
        return out;
    }

    /** Missing/null datetime yields null, not the fabricated epoch-0 (1970-01-01) timestamp. */
    private static Instant epochSeconds(JsonNode n) {
        if (n.isMissingNode() || n.isNull()) return null;
        return Instant.ofEpochSecond(n.asLong(0));
    }
}
