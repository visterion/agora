package de.visterion.agora.fetch.edgar;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.TtlCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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

    private static final javax.xml.parsers.DocumentBuilderFactory DBF = hardenedDbf();

    private static javax.xml.parsers.DocumentBuilderFactory hardenedDbf() {
        var dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        try {
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (javax.xml.parsers.ParserConfigurationException ignored) { /* best effort */ }
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);
        return dbf;
    }

    private final RestClient http;
    private final RestClient archiveHttp;
    private final String archiveBase;
    private final TtlCache<String, List<FilingHit>> searchCache;
    private final TtlCache<String, List<Form4Transaction>> form4Cache;

    @Autowired
    public EdgarSearchService(
            @Value("${agora.data.edgar.user-agent}") String userAgent,
            @Value("${agora.data.edgar.efts-base-url:https://efts.sec.gov}") String eftsBase,
            @Value("${agora.data.edgar.archive-base:https://www.sec.gov}") String archiveBase,
            @Value("${agora.data.cache.ttl.filings-seconds:3600}") long ttlSeconds) {
        this(RestClient.builder().baseUrl(eftsBase).defaultHeader("User-Agent", userAgent).build(),
                RestClient.builder().baseUrl(archiveBase).defaultHeader("User-Agent", userAgent).build(),
                archiveBase, ttlSeconds, System::currentTimeMillis);
    }

    // Test constructor: pre-built efts RestClient (User-Agent already set) + archive base.
    // Builds a UA-less archive client on archiveBase for the Form-4 XML fetch.
    EdgarSearchService(RestClient http, String archiveBase, long ttlSeconds, LongSupplier now) {
        this(http, RestClient.builder().baseUrl(archiveBase).build(), archiveBase, ttlSeconds, now);
    }

    // Full constructor: explicit efts + archive RestClients.
    EdgarSearchService(RestClient http, RestClient archiveHttp, String archiveBase, long ttlSeconds, LongSupplier now) {
        this.http = http;
        this.archiveHttp = archiveHttp;
        this.archiveBase = archiveBase;
        this.searchCache = new TtlCache<>(ttlSeconds * 1000L, now);
        this.form4Cache = new TtlCache<>(ttlSeconds * 1000L, now);
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

    /**
     * Non-derivative Form-4 transactions filed in the window. efts search for forms=4,
     * then per-hit Form-4 XML fetch + DOM parse. Malformed hits/XML are skipped (never
     * throw per-hit); an efts search failure surfaces as {@link MarketDataException}.
     */
    public List<Form4Transaction> form4Transactions(LocalDate from, LocalDate to, int limit) {
        String key = "form4:" + from + ":" + to + ":" + limit;
        return form4Cache.get(key, () -> fetchForm4(from, to, limit));
    }

    private List<Form4Transaction> fetchForm4(LocalDate from, LocalDate to, int limit) {
        List<FilingHit> hits = search(List.of("4"), null, from, to, limit);
        List<Form4Transaction> out = new ArrayList<>();
        for (FilingHit hit : hits) {
            if (out.size() >= limit) break;
            try {
                parseForm4(hit, out);
            } catch (Exception e) {
                // skip malformed individual filings; continue
            }
        }
        return out;
    }

    private void parseForm4(FilingHit hit, List<Form4Transaction> out) throws Exception {
        String ticker = hit.ticker();
        if (ticker == null || ticker.isEmpty()) return;
        if (hit.url() == null || hit.url().isEmpty()) return;

        String xml;
        try {
            // hit.url() is absolute (archiveBase + /Archives/...); archiveHttp's baseUrl == archiveBase resolves it correctly.
            xml = archiveHttp.get().uri(hit.url()).retrieve().body(String.class);
        } catch (Exception e) {
            return;
        }
        if (xml == null) return;

        var doc = DBF.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        var owners = doc.getElementsByTagName("reportingOwner");
        String filerName = "";
        String filerRole = "";
        if (owners.getLength() > 0) {
            var owner = (org.w3c.dom.Element) owners.item(0);
            var names = owner.getElementsByTagName("rptOwnerName");
            if (names.getLength() > 0) filerName = names.item(0).getTextContent().trim();
            var titles = owner.getElementsByTagName("officerTitle");
            if (titles.getLength() > 0) filerRole = titles.item(0).getTextContent().trim();
        }

        var transactions = doc.getElementsByTagName("nonDerivativeTransaction");
        for (int i = 0; i < transactions.getLength(); i++) {
            var tx = (org.w3c.dom.Element) transactions.item(i);
            String code = textOf(tx, "transactionCode");
            String dateStr = valueOf(tx, "transactionDate");
            String sharesStr = valueOf(tx, "transactionShares");
            String priceStr = valueOf(tx, "transactionPricePerShare");
            if (dateStr.isEmpty() || sharesStr.isEmpty()) continue;
            BigDecimal shares = new BigDecimal(sharesStr);
            BigDecimal price = priceStr.isEmpty() ? BigDecimal.ZERO : new BigDecimal(priceStr);
            BigDecimal dollar = shares.multiply(price);
            out.add(new Form4Transaction(
                    ticker, filerName, filerRole,
                    LocalDate.parse(dateStr), shares, dollar, code
            ));
        }
    }

    private static String textOf(org.w3c.dom.Element parent, String tag) {
        var nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }

    private static String valueOf(org.w3c.dom.Element parent, String tag) {
        var nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) return "";
        var inner = ((org.w3c.dom.Element) nodes.item(0)).getElementsByTagName("value");
        return inner.getLength() == 0
                ? nodes.item(0).getTextContent().trim()
                : inner.item(0).getTextContent().trim();
    }
}
