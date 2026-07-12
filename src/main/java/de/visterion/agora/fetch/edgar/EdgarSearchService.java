package de.visterion.agora.fetch.edgar;

import de.visterion.agora.data.DataHttp;
import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.TtlCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
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

    private static final Logger log = LoggerFactory.getLogger(EdgarSearchService.class);

    /** EFTS page size requested per call. */
    private static final int PAGE_SIZE = 100;
    /** Hard guard on total hits fetched across pages, regardless of requested limit. */
    private static final int HARD_FETCH_CAP = 1000;
    /** SEC EDGAR asks for <=10 req/s; ~110ms spacing keeps sequential archive GETs under that. */
    private static final long THROTTLE_MS = 110;
    /** Aggregate deadline for a single form4Transactions() call's sequential archive GETs. */
    private static final long FORM4_DEADLINE_MS = 30_000;
    /** Form-4 search window is widened this many days each side of [from,to] to catch late filings
     *  (the transaction-date filter below narrows back to the caller's exact window). */
    private static final long FORM4_WINDOW_PAD_DAYS = 10;
    private static final long DEFAULT_MAX_FILING_BYTES = 5L * 1024 * 1024;

    @FunctionalInterface
    interface Sleeper { void sleep(long millis) throws InterruptedException; }

    private static final Sleeper REAL_SLEEPER = Thread::sleep;

    private static javax.xml.parsers.DocumentBuilder newDocumentBuilder() {
        var dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        try {
            // Secure processing caps entity-expansion (billion-laughs) via the JAXP limits — defence
            // in depth now that DOCTYPE is allowed below.
            dbf.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            // DOCTYPE declarations are benign on their own (many real Form 4s carry one) — allow
            // them, but keep the external-entity/expansion protections that actually prevent XXE.
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (javax.xml.parsers.ParserConfigurationException ignored) { /* best effort */ }
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);
        try {
            return dbf.newDocumentBuilder();
        } catch (javax.xml.parsers.ParserConfigurationException e) {
            throw new IllegalStateException("failed to configure EDGAR XML parser", e);
        }
    }

    // DocumentBuilderFactory/DocumentBuilder are not guaranteed thread-safe (JAXP). One builder
    // per thread avoids concurrent newDocumentBuilder()/parse() corruption under parallel requests.
    private static final ThreadLocal<javax.xml.parsers.DocumentBuilder> DOC_BUILDER =
            ThreadLocal.withInitial(EdgarSearchService::newDocumentBuilder);

    private final RestClient http;
    private final RestClient archiveHttp;
    private final String archiveBase;
    private final LongSupplier now;
    private final Sleeper sleeper;
    private final long maxFilingBytes;
    private final TtlCache<String, List<FilingHit>> searchCache;
    private final TtlCache<String, Form4Result> form4Cache;
    private final TtlCache<String, FilingText> filingTextCache;

    @Autowired
    public EdgarSearchService(
            @Value("${agora.data.edgar.user-agent}") String userAgent,
            @Value("${agora.data.edgar.efts-base-url:https://efts.sec.gov}") String eftsBase,
            @Value("${agora.data.edgar.archive-base:https://www.sec.gov}") String archiveBase,
            @Value("${agora.data.cache.ttl.filings-seconds:3600}") long ttlSeconds,
            @Value("${agora.fetch.timeout-ms:15000}") long timeoutMs) {
        this(buildHttp(eftsBase, userAgent, timeoutMs),
                buildHttp(archiveBase, userAgent, timeoutMs),
                archiveBase, ttlSeconds, System::currentTimeMillis);
    }

    private static RestClient buildHttp(String baseUrl, String userAgent, long timeoutMs) {
        JdkClientHttpRequestFactory rf = DataHttp.requestFactory(timeoutMs);
        return RestClient.builder()
                .requestFactory(rf)
                .baseUrl(baseUrl)
                .defaultHeader("User-Agent", userAgent)
                .build();
    }

    // Test constructor: pre-built efts RestClient (User-Agent already set) + archive base.
    // Builds a UA-less archive client on archiveBase for the Form-4 XML fetch.
    EdgarSearchService(RestClient http, String archiveBase, long ttlSeconds, LongSupplier now) {
        this(http, RestClient.builder().baseUrl(archiveBase).build(), archiveBase, ttlSeconds, now);
    }

    // Full constructor: explicit efts + archive RestClients, real sleeper + default size cap.
    EdgarSearchService(RestClient http, RestClient archiveHttp, String archiveBase, long ttlSeconds, LongSupplier now) {
        this(http, archiveHttp, archiveBase, ttlSeconds, now, REAL_SLEEPER, DEFAULT_MAX_FILING_BYTES);
    }

    // Test constructor: full control over the throttle sleeper and the filing-body size cap, so
    // throttle/deadline/cap tests run fast and deterministic (no real sleeping, no multi-MB bodies).
    EdgarSearchService(RestClient http, RestClient archiveHttp, String archiveBase, long ttlSeconds, LongSupplier now,
                        Sleeper sleeper, long maxFilingBytes) {
        this.http = http;
        this.archiveHttp = archiveHttp;
        this.archiveBase = archiveBase;
        this.now = now;
        this.sleeper = sleeper;
        this.maxFilingBytes = maxFilingBytes;
        this.searchCache = new TtlCache<>(ttlSeconds * 1000L, 512, now);
        this.form4Cache = new TtlCache<>(ttlSeconds * 1000L, 512, now);
        // Filing text bodies run up to ~24KB each — keep this cache small to bound heap.
        this.filingTextCache = new TtlCache<>(ttlSeconds * 1000L, 32, now);
    }

    /** Full-text filing search on efts. ticker on a hit may be empty (fresh registrations). */
    public List<FilingHit> search(List<String> forms, String query, LocalDate from, LocalDate to, int limit) {
        return search(forms, query, null, from, to, limit);
    }

    /** Like {@link #search(List, String, LocalDate, LocalDate, int)} but additionally filtered to
     *  filings involving the given entity CIK (efts {@code ciks} filter; zero-padded 10 digits).
     *  For ownership forms (3/4/5) the issuer is one of the filing entities, so an issuer CIK
     *  matches its Form-4 filings. {@code cik} null/blank means no entity filter. */
    public List<FilingHit> search(List<String> forms, String query, String cik, LocalDate from, LocalDate to, int limit) {
        String formsCsv = String.join(",", forms);
        String key = cacheKey("search", formsCsv, query, cik, str(from), str(to), String.valueOf(limit));
        return searchCache.get(key, () -> fetchSearch(formsCsv, query, cik, from, to, limit));
    }

    // Length-prefixed segment join — a plain ":"-joined key collides whenever a field itself
    // contains ":" (e.g. forms="a:b" + query="c" vs forms="a" + query="b:c" produce the same
    // naive key). Prefixing each segment with its length makes the join unambiguous.
    private static String cacheKey(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            String v = p == null ? "" : p;
            sb.append(v.length()).append(':').append(v);
        }
        return sb.toString();
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }

    private List<FilingHit> fetchSearch(String formsCsv, String query, String cik, LocalDate from, LocalDate to, int limit) {
        List<FilingHit> out = new ArrayList<>();
        int offset = 0;
        boolean capped = false;
        while (out.size() < limit) {
            if (offset >= HARD_FETCH_CAP) { capped = true; break; }
            JsonNode search = fetchPage(formsCsv, query, cik, from, to, offset);
            JsonNode hitsNode = search == null ? null : search.path("hits");
            JsonNode hits = hitsNode == null ? null : hitsNode.path("hits");
            if (hits == null || !hits.isArray() || hits.isEmpty()) break;
            int pageCount = hits.size();
            for (JsonNode hit : hits) {
                if (out.size() >= limit) break;
                try {
                    FilingHit f = parseHit(hit);
                    if (f != null) out.add(f);
                } catch (Exception e) {
                    // skip malformed individual hit
                }
            }
            offset += pageCount;
            // Stop once the offset reaches EFTS's reported total (real ES/EFTS pagination
            // semantics). A response that doesn't report a total is treated as exhausted after
            // this one page — a safe default, never an infinite/runaway pagination loop.
            long total = hitsNode.path("total").path("value").asLong(0);
            if (offset >= total) break;
        }
        if (capped) {
            log.debug("EFTS search capped at {} fetched hits (forms={}, query={})", HARD_FETCH_CAP, formsCsv, query);
        }
        return out;
    }

    private JsonNode fetchPage(String formsCsv, String query, String cik, LocalDate from, LocalDate to, int offset) {
        try {
            return http.get()
                    .uri(uri -> {
                        uri.path("/LATEST/search-index")
                                .queryParam("forms", formsCsv)
                                .queryParam("from", offset)
                                .queryParam("size", PAGE_SIZE);
                        if (from != null || to != null) {
                            uri.queryParam("dateRange", "custom")
                                    .queryParam("startdt", from == null ? "" : from.toString())
                                    .queryParam("enddt", to == null ? "" : to.toString());
                        }
                        if (query != null && !query.isBlank()) uri.queryParam("q", query);
                        if (cik != null && !cik.isBlank()) uri.queryParam("ciks", cik);
                        return uri.build();
                    })
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "EDGAR search unreachable: " + e.getMessage(), e);
        }
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
            // Archive-path CIK comes from _source.ciks[0], NOT the accession prefix: the accession
            // prefix is the filing-agent CIK, which is often not the archive path CIK. Long.parseLong
            // strips the leading zeros that SEC archive paths omit (/data/320193/, not /data/0000320193/).
            JsonNode ciks = src.path("ciks");
            if (ciks.isArray() && !ciks.isEmpty()) {
                try {
                    String accessionNoDashes = accession.replace("-", "");
                    long cik = Long.parseLong(ciks.get(0).asString(""));
                    url = archiveBase + "/Archives/edgar/data/" + cik + "/" + accessionNoDashes + "/" + parts[1];
                } catch (Exception e) {
                    // url stays empty when ciks[0] is non-numeric; hit is still returned
                }
            }
        }

        if (company.isEmpty() && ticker.isEmpty()) return null;
        return new FilingHit(ticker, company, form, filedDate, accession, url);
    }

    /**
     * Form-4 transaction list plus a truncation flag. {@code truncated} is true whenever the
     * result may be incomplete: the aggregate fetch deadline hit, the transaction {@code limit}
     * stopped the hit loop early, or the underlying search returned a full {@code limit}-sized
     * hit list (more filings may exist beyond the cut). Consumers must treat a truncated result
     * as a partial window, never as the complete history. Note: because a filing's transactions
     * are added atomically, the list may slightly overshoot {@code limit} (no trimming); an
     * overshoot that leaves hits unprocessed is likewise marked truncated.
     */
    public record Form4Result(List<Form4Transaction> transactions, boolean truncated) {}

    /**
     * Non-derivative Form-4 (and 4/A amendment) transactions whose <em>transaction</em> date falls
     * in [from,to] — filed in the window OR filed late (the underlying search widens by
     * {@value #FORM4_WINDOW_PAD_DAYS} days each side to catch late-filed-but-in-window trades).
     * efts search for forms=4,4/A, then per-hit Form-4 XML fetch + DOM parse, throttled to stay
     * under SEC's request-rate limit with an aggregate deadline. Malformed hits/XML are skipped
     * (never throw per-hit); an efts search failure surfaces as {@link MarketDataException}.
     *
     * <p>Ordering: EFTS has no documented usable sort parameter (a simple {@code sort=} value is
     * rejected), but its default order is deterministic — {@code file_date} descending with the
     * hit {@code _id} as tiebreak (verified 2026-07). A limit/deadline cut therefore drops the
     * OLDEST filings of the window, never a random subset.
     *
     * <p>Price fail-soft (intentional change, 2026-07): an absent/empty/unparsable
     * {@code transactionPricePerShare} no longer discards the filing — the transaction is kept
     * with {@code price=null} and {@code dollarValue=0}. Previously such filings were skipped
     * entirely by the per-hit catch.
     */
    public Form4Result form4Transactions(LocalDate from, LocalDate to, int limit) {
        String key = cacheKey("form4", str(from), str(to), String.valueOf(limit));
        return form4Cache.get(key, () -> fetchForm4(null, from, to, limit));
    }

    /**
     * Like {@link #form4Transactions(LocalDate, LocalDate, int)} but restricted to one company:
     * only filings involving the given entity CIK (the issuer is a filing entity on every Form 4,
     * so an issuer CIK returns that company's Form-4 stream). Same widen-then-narrow window
     * handling, throttle, aggregate deadline and truncation semantics as the market-wide variant.
     */
    public Form4Result form4TransactionsByCik(String cik, LocalDate from, LocalDate to, int limit) {
        String key = cacheKey("form4cik", cik, str(from), str(to), String.valueOf(limit));
        return form4Cache.get(key, () -> fetchForm4(cik, from, to, limit));
    }

    private Form4Result fetchForm4(String cik, LocalDate from, LocalDate to, int limit) {
        LocalDate searchFrom = from == null ? null : from.minusDays(FORM4_WINDOW_PAD_DAYS);
        LocalDate searchTo = to == null ? null : to.plusDays(FORM4_WINDOW_PAD_DAYS);
        List<FilingHit> hits = search(List.of("4", "4/A"), null, cik, searchFrom, searchTo, limit);
        List<Form4Transaction> out = new ArrayList<>();
        // A full limit-sized hit list means the search itself was cut (more filings may exist,
        // including a silent HARD_FETCH_CAP stop) — never report such a window as complete.
        boolean truncated = hits.size() >= limit;
        long deadline = now.getAsLong() + FORM4_DEADLINE_MS;
        boolean first = true;
        for (FilingHit hit : hits) {
            if (out.size() >= limit) {
                truncated = true;   // hits remain unprocessed beyond the transaction limit
                break;
            }
            if (now.getAsLong() >= deadline) {
                truncated = true;
                break;
            }
            if (!first) {
                try {
                    sleeper.sleep(THROTTLE_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    truncated = true;
                    break;
                }
            }
            first = false;
            try {
                parseForm4(hit, out, from, to);
            } catch (Exception e) {
                // skip malformed individual filings; continue
            }
        }
        return new Form4Result(out, truncated);
    }

    /** A filing's extracted summary/term-sheet text plus extraction metadata. */
    public record FilingText(String text, boolean sectionFound, boolean truncated, int charCount, String sourceUrl) {}

    /**
     * Fetch a filing's primary document from the SEC archive and extract its summary/term-sheet
     * text. {@code url} MUST be an archive URL under the configured archive base (SSRF guard).
     * Throws {@link MarketDataException} on a non-archive url, a fetch failure, an oversized
     * body (> {@value #DEFAULT_MAX_FILING_BYTES} bytes by default), or an empty document.
     */
    public FilingText filingText(String url) {
        if (url == null || !url.startsWith(archiveBase + "/Archives/")) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "not an SEC archive url: " + url, null);
        }
        return filingTextCache.get(cacheKey("text", url), () -> fetchFilingText(url));
    }

    private FilingText fetchFilingText(String url) {
        String raw;
        try {
            raw = archiveHttp.get().uri(url).exchange((request, response) -> {
                long contentLength = response.getHeaders().getContentLength();
                if (contentLength > maxFilingBytes) {
                    throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                            "filing document too large (" + contentLength + " bytes): " + url, null);
                }
                try (InputStream body = response.getBody()) {
                    // Bounded read regardless of (possibly absent/lying) Content-Length: never
                    // buffer more than maxFilingBytes+1 bytes, so we can detect an over-cap body.
                    byte[] buf = body.readNBytes((int) Math.min(maxFilingBytes + 1, Integer.MAX_VALUE));
                    if (buf.length > maxFilingBytes) {
                        throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                                "filing document exceeds size cap: " + url, null);
                    }
                    return new String(buf, StandardCharsets.UTF_8);
                }
            });
        } catch (MarketDataException e) {
            throw e;
        } catch (Exception e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "filing fetch failed: " + e.getMessage(), e);
        }
        if (raw == null || raw.isBlank()) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "empty filing document: " + url, null);
        }
        var ex = FilingTextExtractor.extract(raw);
        return new FilingText(ex.text(), ex.sectionFound(), ex.truncated(), ex.text().length(), url);
    }

    private void parseForm4(FilingHit hit, List<Form4Transaction> out, LocalDate from, LocalDate to) throws Exception {
        if (hit.url() == null || hit.url().isEmpty()) return;

        String xml;
        try {
            // hit.url() is absolute (archiveBase + /Archives/...); archiveHttp's baseUrl == archiveBase resolves it correctly.
            xml = archiveHttp.get().uri(hit.url()).retrieve().body(String.class);
        } catch (Exception e) {
            return;
        }
        if (xml == null) return;

        var builder = DOC_BUILDER.get();
        builder.reset();
        var doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        // The efts _source has no ticker; it lives in the fetched Form-4 XML. May be empty
        // (e.g. some amendments) — emit the transaction anyway, filer/issuer are still known.
        String ticker = "";
        var symbols = doc.getElementsByTagName("issuerTradingSymbol");
        if (symbols.getLength() > 0) ticker = symbols.item(0).getTextContent().trim();

        // Read ALL reportingOwner elements: a Form 4 can list several co-filers (e.g. a trust plus
        // the individual trustee). Join their names; take the first non-empty officer title/role
        // and the first non-empty owner CIK.
        var owners = doc.getElementsByTagName("reportingOwner");
        List<String> filerNames = new ArrayList<>();
        String filerRole = "";
        String filerCik = "";
        for (int i = 0; i < owners.getLength(); i++) {
            var owner = (org.w3c.dom.Element) owners.item(i);
            var names = owner.getElementsByTagName("rptOwnerName");
            if (names.getLength() > 0) {
                String n = names.item(0).getTextContent().trim();
                if (!n.isEmpty()) filerNames.add(n);
            }
            if (filerRole.isEmpty()) {
                var titles = owner.getElementsByTagName("officerTitle");
                if (titles.getLength() > 0) {
                    String t = titles.item(0).getTextContent().trim();
                    if (!t.isEmpty()) filerRole = t;
                }
            }
            if (filerCik.isEmpty()) {
                var ciks = owner.getElementsByTagName("rptOwnerCik");
                if (ciks.getLength() > 0) filerCik = ciks.item(0).getTextContent().trim();
            }
        }
        String filerName = String.join(", ", filerNames);

        // Filing-level Rule 10b5-1(c) checkbox (mandatory on filings since 2023). Tri-state:
        // absent on pre-2023 filings → null ("unknown"), never coerced to false.
        Boolean aff10b5One = parseXmlBoolean(textOf(doc.getDocumentElement(), "aff10b5One"));

        var transactions = doc.getElementsByTagName("nonDerivativeTransaction");
        for (int i = 0; i < transactions.getLength(); i++) {
            var tx = (org.w3c.dom.Element) transactions.item(i);
            String code = textOf(tx, "transactionCode");
            String dateStr = valueOf(tx, "transactionDate");
            String sharesStr = valueOf(tx, "transactionShares");
            String priceStr = valueOf(tx, "transactionPricePerShare");
            String acquiredDisposedCode = valueOf(tx, "transactionAcquiredDisposedCode");
            if (dateStr.isEmpty() || sharesStr.isEmpty()) continue;
            LocalDate txDate;
            try {
                txDate = LocalDate.parse(dateStr);
            } catch (Exception e) {
                continue;
            }
            // Filter on the TRANSACTION date, not the filing date: the search window above was
            // widened to catch late filings, so narrow back down to the caller's exact window here.
            if (from != null && txDate.isBefore(from)) continue;
            if (to != null && txDate.isAfter(to)) continue;
            BigDecimal shares = new BigDecimal(sharesStr);
            BigDecimal price = bdOrNull(priceStr);
            BigDecimal dollar = shares.multiply(price == null ? BigDecimal.ZERO : price);
            // postTransactionAmounts/sharesOwnedFollowingTransaction — fail-soft nullable.
            BigDecimal sharesOwnedFollowing = bdOrNull(valueOf(tx, "sharesOwnedFollowingTransaction"));
            out.add(new Form4Transaction(
                    ticker, filerName, filerRole,
                    txDate, shares, dollar, code, acquiredDisposedCode, hit.form(),
                    price, sharesOwnedFollowing, aff10b5One, filerCik
            ));
        }
    }

    /** SEC XML boolean ("1"/"true"/"0"/"false"); anything else, including absent/empty → null. */
    private static Boolean parseXmlBoolean(String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase()) {
            case "1", "true" -> Boolean.TRUE;
            case "0", "false" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** Fail-soft decimal: empty/unparsable → null (never throws). */
    private static BigDecimal bdOrNull(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            return null;
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
