package de.visterion.agora.fetch.edgar;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves ticker -> zero-padded 10-digit CIK from SEC company_tickers.json (loaded once, cached). */
@Component
public class EdgarCikResolver {

    private final RestClient http;
    private volatile Map<String, String> cache;

    @Autowired
    public EdgarCikResolver(@Value("${agora.data.edgar.user-agent}") String userAgent) {
        this.http = RestClient.builder()
                .baseUrl("https://www.sec.gov")
                .defaultHeader("User-Agent", userAgent)
                .build();
    }

    // Test constructor: RestClient with base url already set.
    EdgarCikResolver(RestClient http) { this.http = http; }

    public Optional<String> cik(String ticker) {
        if (ticker == null || ticker.isBlank()) return Optional.empty();
        return Optional.ofNullable(map().get(ticker.toUpperCase()));
    }

    private Map<String, String> map() {
        Map<String, String> local = cache;
        if (local != null) return local;
        synchronized (this) {
            if (cache != null) return cache;
            Map<String, String> built = new ConcurrentHashMap<>();
            try {
                JsonNode root = http.get().uri("/files/company_tickers.json")
                        .retrieve().body(JsonNode.class);
                if (root != null) {
                    for (JsonNode entry : root) {
                        String ticker = entry.path("ticker").asString("").toUpperCase();
                        long cik = entry.path("cik_str").asLong(0);
                        if (!ticker.isEmpty() && cik > 0) {
                            built.put(ticker, String.format("%010d", cik));
                        }
                    }
                }
            } catch (Exception e) {
                return built;
            }
            if (!built.isEmpty()) {
                cache = built;
            }
            return built;
        }
    }
}
