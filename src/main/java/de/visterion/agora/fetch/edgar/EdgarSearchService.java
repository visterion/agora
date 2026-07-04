package de.visterion.agora.fetch.edgar;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.TtlCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * SEC EDGAR full-text-search (efts.sec.gov) client: generic filing search plus
 * the Form-4 fetch/parse. Neutral, pattern-agnostic — callers supply the form
 * types and window. Reuses {@link de.visterion.agora.data.MarketDataException}
 * for graceful degradation.
 */
@Component
public class EdgarSearchService {

    private final RestClient http;
    private final String archiveBase;
    private final TtlCache<String, List<FilingHit>> searchCache;

    @Autowired
    public EdgarSearchService(
            @Value("${agora.data.edgar.user-agent}") String userAgent,
            @Value("${agora.data.edgar.efts-base-url:https://efts.sec.gov}") String eftsBase,
            @Value("${agora.data.edgar.archive-base:https://www.sec.gov}") String archiveBase,
            @Value("${agora.data.cache.ttl.filings-seconds:3600}") long ttlSeconds) {
        this(RestClient.builder().baseUrl(eftsBase).defaultHeader("User-Agent", userAgent).build(),
                archiveBase, ttlSeconds, System::currentTimeMillis);
    }

    // Test constructor: pre-built efts RestClient (User-Agent already set) + archive base.
    EdgarSearchService(RestClient http, String archiveBase, long ttlSeconds, LongSupplier now) {
        this.http = http;
        this.archiveBase = archiveBase;
        this.searchCache = new TtlCache<>(ttlSeconds * 1000L, now);
    }

    /** Full-text filing search on efts. ticker on a hit may be empty (fresh registrations). */
    public List<FilingHit> search(List<String> forms, String query, LocalDate from, LocalDate to, int limit) {
        String formsCsv = String.join(",", forms);
        String key = "search:" + formsCsv + ":" + query + ":" + from + ":" + to + ":" + limit;
        return searchCache.get(key, () -> fetchSearch(formsCsv, query, from, to, limit));
    }

    private List<FilingHit> fetchSearch(String formsCsv, String query, LocalDate from, LocalDate to, int limit) {
        JsonNode search;
        try {
            search = http.get()
                    .uri(uri -> {
                        uri.path("/LATEST/search-index")
                                .queryParam("forms", formsCsv)
                                .queryParam("dateRange", "custom")
                                .queryParam("startdt", from == null ? "" : from.toString())
                                .queryParam("enddt", to == null ? "" : to.toString());
                        if (query != null && !query.isBlank()) uri.queryParam("q", query);
                        return uri.build();
                    })
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "EDGAR search unreachable: " + e.getMessage(), e);
        }
        if (search == null) return List.of();
        JsonNode hits = search.path("hits").path("hits");
        if (!hits.isArray() || hits.isEmpty()) return List.of();

        List<FilingHit> out = new ArrayList<>();
        for (JsonNode hit : hits) {
            if (out.size() >= limit) break;
            try {
                FilingHit f = parseHit(hit);
                if (f != null) out.add(f);
            } catch (Exception e) {
                // skip malformed individual hit
            }
        }
        return out;
    }

    private FilingHit parseHit(JsonNode hit) {
        JsonNode src = hit.path("_source");

        String company = "";
        JsonNode names = src.path("display_names");
        if (names.isArray() && !names.isEmpty()) {
            company = names.get(0).asString("");
            int p = company.indexOf(" (CIK");
            if (p > 0) company = company.substring(0, p).trim();
        }

        String ticker = "";
        JsonNode tn = src.path("tickers");
        if (tn.isArray() && !tn.isEmpty()) ticker = tn.get(0).asString("").toUpperCase();

        LocalDate filedDate;
        try {
            filedDate = LocalDate.parse(src.path("file_date").asString(""));
        } catch (Exception e) {
            return null;
        }
        String form = src.path("file_type").asString("");

        String id = hit.path("_id").asString("");
        String accession = "";
        String url = "";
        String[] parts = id.split(":");
        if (parts.length == 2) {
            accession = parts[0];
            try {
                String accessionNoDashes = accession.replace("-", "");
                long cik = Long.parseLong(accessionNoDashes.substring(0, Math.min(10, accessionNoDashes.length())));
                url = archiveBase + "/Archives/edgar/data/" + cik + "/" + accessionNoDashes + "/" + parts[1];
            } catch (Exception e) {
                // url stays empty when the accession is non-numeric; hit is still returned
            }
        }

        if (company.isEmpty() && ticker.isEmpty()) return null;
        return new FilingHit(ticker, company, form, filedDate, accession, url);
    }
}
