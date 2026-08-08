package de.visterion.agora.fetch.search;

import de.visterion.agora.data.DataHttp;
import de.visterion.agora.data.MarketDataException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Yahoo instrument search (/v1/finance/search). Keyless, but a browser User-Agent is
 * mandatory — without it Yahoo answers 429 "Edge: Too Many Requests" (measured 2026-08-08).
 * Reuses agora.data.yahoo.base-url; NO second Yahoo host property (see application.yaml).
 */
@Component
public class YahooSearchClient {

    private final RestClient client;

    /** Test constructor: pass a pre-built client (e.g. bound to MockRestServiceServer). */
    YahooSearchClient(RestClient client) {
        this.client = client;
    }

    @Autowired
    public YahooSearchClient(
            @Value("${agora.data.yahoo.base-url}") String baseUrl,
            @Value("${agora.data.yahoo.user-agent}") String userAgent,
            @Value("${agora.data.provider-timeout-ms:4000}") long timeoutMs) {
        this(DataHttp.clientBuilder(timeoutMs)
                .baseUrl(baseUrl)
                .defaultHeader("User-Agent", userAgent)
                .build());
    }

    /** One upstream search. Throws MarketDataException(UNAVAILABLE) on any failure. */
    public List<SearchHit> search(String query, int quotesCount) {
        try {
            JsonNode body = client.get()
                    .uri(uri -> uri.path("/v1/finance/search")
                            .queryParam("q", query)
                            .queryParam("quotesCount", quotesCount)
                            .queryParam("newsCount", 0)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null) {
                throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                        "yahoo search: empty response", null);
            }
            return YahooSearchParser.parse(body);
        } catch (MarketDataException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "yahoo search failed: " + e.getMessage(), e);
        }
    }
}
