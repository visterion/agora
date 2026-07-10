package de.visterion.agora.fetch.reference;

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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wikipedia "List of S&P 500 companies" service. Fetches the page wikitext via the
 * MediaWiki API and parses the main constituents table (Symbol, Security, GICS Sector,
 * Date added columns). Metadata-only, no HTML-parser dependency. Any fetch/parse failure
 * throws {@link MarketDataException} (UNAVAILABLE); successful loads are cached per-family.
 */
@Component
public class WikipediaService {

    private static final Logger log = LoggerFactory.getLogger(WikipediaService.class);
    private static final Pattern ISO_DATE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern WIKILINK = Pattern.compile("\\[\\[([^\\]]+)\\]\\]");
    private static final Pattern TEMPLATE = Pattern.compile("\\{\\{([^{}]+)\\}\\}");
    private static final Pattern REF = Pattern.compile("<ref.*?(</ref>|/>)");

    private final RestClient http;
    private final String pageTitle;
    private final TtlCache<String, List<Constituent>> cache;

    @Autowired
    public WikipediaService(
            @Value("${agora.data.wikipedia.base-url:https://en.wikipedia.org}") String baseUrl,
            @Value("${agora.data.wikipedia.user-agent:agora/1.0 (research)}") String userAgent,
            @Value("${agora.data.wikipedia.sp500-page:List of S&P 500 companies}") String pageTitle,
            @Value("${agora.data.cache.ttl.constituents-seconds:86400}") long ttlSeconds,
            @Value("${agora.fetch.timeout-ms:15000}") long timeoutMs) {
        this(buildHttp(baseUrl, userAgent, timeoutMs), pageTitle, ttlSeconds, System::currentTimeMillis);
    }

    private static RestClient buildHttp(String baseUrl, String userAgent, long timeoutMs) {
        JdkClientHttpRequestFactory rf = DataHttp.requestFactory(timeoutMs);
        return RestClient.builder()
                .requestFactory(rf)
                .baseUrl(baseUrl)
                .defaultHeader("User-Agent", userAgent)
                .build();
    }

    // Test constructor: pre-built RestClient + page title + ttl + clock.
    WikipediaService(RestClient http, String pageTitle, long ttlSeconds, LongSupplier now) {
        this.http = http;
        this.pageTitle = pageTitle;
        // Single well-known key ("constituents:sp500") today; a small cap still allows
        // headroom without pretending this cache needs to scale.
        this.cache = new TtlCache<>(ttlSeconds * 1000L, 8, now);
    }

    /** Constituents of a stock index. Only "sp500" (case-insensitive; null/blank treated as sp500) is known. */
    public List<Constituent> constituents(String index) {
        if (index != null && !index.isBlank() && !index.trim().equalsIgnoreCase("sp500"))
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "unknown index: " + index, null);
        return cache.get("constituents:sp500", this::fetchSp500);
    }

    private List<Constituent> fetchSp500() {
        JsonNode body;
        try {
            body = http.get()
                    .uri(uri -> uri.path("/w/api.php")
                            .queryParam("action", "parse")
                            .queryParam("page", pageTitle)
                            .queryParam("prop", "wikitext")
                            .queryParam("format", "json")
                            .queryParam("formatversion", "2")
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.warn("Wikipedia S&P 500 fetch failed: {}", e.getMessage());
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "wikipedia: " + e.getMessage(), e);
        }
        if (body == null)
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "wikipedia: empty response", null);
        JsonNode wt = body.path("parse").path("wikitext");
        if (!wt.isTextual())
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "wikipedia: empty response", null);
        List<Constituent> result;
        try {
            result = parse(wt.asString());
        } catch (Exception e) {
            log.warn("Wikipedia S&P 500 parse failed: {}", e.getMessage());
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "wikipedia: parse failed", e);
        }
        // An empty parse result means the page structure changed (missing anchor/table/columns)
        // rather than a genuine "zero constituents" answer — throw so the failure is never
        // cached as a successful-but-empty index.
        if (result.isEmpty())
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "wikipedia: no constituents parsed", null);
        return result;
    }

    private List<Constituent> parse(String wikitext) {
        // Anchor on the table's stable id rather than a column-header phrase: "Date added"
        // also occurs in the lead-section <!--EDITORS...--> comment, which would send the
        // backward {| search past the real table and yield an empty list.
        int anchor = wikitext.indexOf("id=\"constituents\"");
        if (anchor < 0) {
            log.warn("Wikipedia S&P 500: constituents table anchor id=\"constituents\" not found");
            return List.of();
        }
        int tableStart = wikitext.lastIndexOf("{|", anchor);
        int tableEnd = wikitext.indexOf("|}", anchor);
        if (tableStart < 0 || tableEnd < 0 || tableEnd <= tableStart) {
            log.warn("Wikipedia S&P 500: constituents table bounds not found (start={}, end={})", tableStart, tableEnd);
            return List.of();
        }
        String block = wikitext.substring(tableStart, tableEnd);

        String[] rows = block.split("\\n\\|-");
        if (rows.length < 2) return List.of();

        // Locate the header row: the first row that yields `!`-marked header cells.
        // A wikitable may or may not carry a leading `|-` before its header, so we do
        // not assume the header sits at a fixed row index.
        int headerRow = -1;
        List<String> headers = List.of();
        for (int i = 0; i < rows.length; i++) {
            List<String> h = cells(rows[i], '!');
            if (!h.isEmpty()) { headerRow = i; headers = h; break; }
        }
        if (headerRow < 0) return List.of();

        int symIdx = headerIndex(headers, "Symbol", 0);
        int comIdx = headerIndex(headers, "Security", 1);
        int sectorIdx = headerIndex(headers, "GICS Sector", -1);
        int dateIdx = headerIndex(headers, "Date added", -1);
        if (dateIdx < 0) return List.of();

        List<Constituent> out = new ArrayList<>();
        for (int i = headerRow + 1; i < rows.length; i++) {
            List<String> c = cells(rows[i], '|');
            if (dateIdx >= c.size() || symIdx >= c.size()) continue;
            LocalDate d = parseDate(c.get(dateIdx));
            if (d == null) continue;
            String sym = stripWiki(c.get(symIdx));
            if (sym.isEmpty()) continue;
            String com = comIdx < c.size() ? stripWiki(c.get(comIdx)) : "";
            String sector = (sectorIdx >= 0 && sectorIdx < c.size()) ? stripWiki(c.get(sectorIdx)) : null;
            out.add(new Constituent(sym, com, sector, d));
        }
        return out;
    }

    /** Split a wikitext row segment into cell texts. Handles per-line `| cell` and inline `|| `. */
    private static List<String> cells(String segment, char marker) {
        String norm = segment.replace("" + marker + marker, "\n" + marker);
        List<String> out = new ArrayList<>();
        for (String line : norm.split("\\n")) {
            String t = line.strip();
            if (t.isEmpty() || t.charAt(0) != marker) continue;
            out.add(t.substring(1).strip());
        }
        return out;
    }

    private static int headerIndex(List<String> headers, String name, int fallback) {
        for (int i = 0; i < headers.size(); i++) {
            if (stripWiki(headers.get(i)).equalsIgnoreCase(name)) return i;
        }
        return fallback;
    }

    private static LocalDate parseDate(String cell) {
        if (cell == null) return null;
        Matcher m = ISO_DATE.matcher(cell);
        if (!m.find()) return null;
        try { return LocalDate.parse(m.group(1)); } catch (Exception e) { return null; }
    }

    private static String stripWiki(String cell) {
        if (cell == null) return "";
        String s = REF.matcher(cell).replaceAll("");
        // {{NyseSymbol|MMM}} / {{NasdaqSymbol|AOS}} -> last template parameter (the ticker).
        s = replaceInline(TEMPLATE, s, inner -> {
            int pipe = inner.lastIndexOf('|');
            return pipe >= 0 ? inner.substring(pipe + 1) : inner;
        });
        // [[target|display]] -> display, [[target]] -> target, replaced INLINE so surrounding
        // cell text survives: "[[..|GICS]] Sector" -> "GICS Sector" (not just "GICS").
        s = replaceInline(WIKILINK, s, inner -> {
            int pipe = inner.indexOf('|');
            return pipe >= 0 ? inner.substring(pipe + 1) : inner;
        });
        return s.trim();
    }

    /** Replace every match of {@code p} inline with a value derived from its first capture group. */
    private static String replaceInline(Pattern p, String s, java.util.function.UnaryOperator<String> fn) {
        Matcher m = p.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(fn.apply(m.group(1)).trim()));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
