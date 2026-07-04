package de.visterion.agora.fetch.edgar;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.TtlCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

/** SEC EDGAR fetch: recent filings (submissions API) and diluted/basic EPS history (companyconcept). */
@Component
public class EdgarService {

    private final RestClient http;
    private final EdgarCikResolver cikResolver;
    private final TtlCache<String, List<FilingRef>> filingsCache;

    @Autowired
    public EdgarService(@Value("${agora.data.edgar.user-agent}") String userAgent,
                        EdgarCikResolver cikResolver,
                        @Value("${agora.data.cache.ttl.filings-seconds:3600}") long ttlSeconds) {
        this(RestClient.builder().baseUrl("https://data.sec.gov").defaultHeader("User-Agent", userAgent).build(),
                cikResolver, ttlSeconds, System::currentTimeMillis);
    }

    EdgarService(RestClient http, EdgarCikResolver cikResolver, long ttlSeconds, LongSupplier now) {
        this.http = http;
        this.cikResolver = cikResolver;
        this.filingsCache = new TtlCache<>(ttlSeconds * 1000L, now);
    }

    /** cikOrSymbol: 10-digit CIK if all-digits, else resolved via ticker. */
    private String resolveCik(String symbol, String cik) {
        if (cik != null && !cik.isBlank()) return String.format("%010d", Long.parseLong(cik.trim()));
        Optional<String> resolved = cikResolver.cik(symbol);
        if (resolved.isEmpty())
            throw new MarketDataException(MarketDataException.Kind.NOT_FOUND, "no CIK for " + symbol, null);
        return resolved.get();
    }

    public List<FilingRef> filings(String symbol, String cik, String formType, LocalDate from, int limit) {
        String padded = resolveCik(symbol, cik);
        String key = "filings:" + padded + ":" + formType + ":" + from + ":" + limit;
        return filingsCache.get(key, () -> fetchFilings(padded, formType, from, limit));
    }

    private List<FilingRef> fetchFilings(String padded, String formType, LocalDate from, int limit) {
        JsonNode body;
        try {
            body = http.get().uri("/submissions/CIK{cik}.json", padded).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "EDGAR HTTP " + e.getStatusCode(), e);
        } catch (Exception e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "EDGAR unreachable: " + e.getMessage(), e);
        }
        if (body == null) throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "empty EDGAR body", null);
        JsonNode r = body.path("filings").path("recent");
        JsonNode acc = r.path("accessionNumber"), forms = r.path("form"), filed = r.path("filingDate"),
                 report = r.path("reportDate"), docs = r.path("primaryDocument");
        long cikNum = Long.parseLong(padded);
        List<FilingRef> out = new ArrayList<>();
        if (acc.isArray()) {
            for (int i = 0; i < acc.size() && out.size() < limit; i++) {
                String form = forms.path(i).asString("");
                if (formType != null && !formType.isBlank() && !formType.equalsIgnoreCase(form)) continue;
                LocalDate filedDate = parseDate(filed.path(i).asString(""));
                if (filedDate == null) continue;
                if (from != null && filedDate.isBefore(from)) continue;
                String accession = acc.path(i).asString("");
                String noDash = accession.replace("-", "");
                String doc = docs.path(i).asString("");
                LocalDate reportDate = parseDate(report.path(i).asString(""));
                String url = "https://www.sec.gov/Archives/edgar/data/" + cikNum + "/" + noDash + "/" + doc;
                out.add(new FilingRef(accession, form, filedDate, reportDate, doc, url));
            }
        }
        return out;
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.length() < 10) return null;
        try { return LocalDate.parse(s.substring(0, 10)); } catch (Exception e) { return null; }
    }
}
