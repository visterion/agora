package de.visterion.agora.fetch.finnhub;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.TtlCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/** Company news headlines via Finnhub, cached per-family (news TTL). */
@Component
public class NewsService {

    private final FinnhubClient client;
    private final TtlCache<String, List<NewsItem>> cache;

    @Autowired
    public NewsService(FinnhubClient client,
                       @Value("${agora.data.cache.ttl.news-seconds:900}") long ttlSeconds) {
        this(client, ttlSeconds, System::currentTimeMillis);
    }

    NewsService(FinnhubClient client, long ttlSeconds, LongSupplier now) {
        this.client = client;
        this.cache = new TtlCache<>(ttlSeconds * 1000L, now);
    }

    public List<NewsItem> companyNews(String symbol, LocalDate from, LocalDate to) {
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
                            .queryParam("token", client.token())
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "finnhub company-news unreachable: " + e.getMessage(), e);
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
                    Instant.ofEpochSecond(n.path("datetime").asLong(0)),
                    n.path("url").asString("")));
        }
        return out;
    }
}
