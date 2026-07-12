package de.visterion.agora.fetch.reference.change;

import de.visterion.agora.data.DataHttp;
import de.visterion.agora.data.TtlCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Index constituent changes derived from S&amp;P Dow Jones Indices' public press-release RSS
 * feed. Built like {@code WikipediaService}: a {@link RestClient} over {@link DataHttp}'s
 * request factory plus {@link TtlCache}. Two cached stages:
 *
 * <ol>
 *   <li>Fetch the RSS list (short TTL) and parse the well-formed RSS 2.0 envelope with a
 *       secure {@link DocumentBuilderFactory} (XXE off). Only the prose <em>inside</em> each
 *       linked release is regex-scraped later — the outer XML is parsed properly.</li>
 *   <li>For each item whose title matches an externalised add/remove pattern, fetch the full
 *       release body (long TTL — releases are immutable), {@linkplain #normalizeHtml(String)
 *       normalise the HTML to plain text} (entities decoded, tags stripped, whitespace
 *       collapsed) and regex out the effective date and the ticker(s) from the plain text.
 *       Matching raw HTML would miss real-world variance like {@code NYSE:&nbsp;SEI}, inline
 *       tags, and line breaks mid-sentence.</li>
 * </ol>
 *
 * <p>Fail-soft per item: a title that matches but whose body yields no ticker or no effective
 * date is dropped (WARN + URL), never half-emitted; an unexpected error while parsing one item
 * drops only that item. The provider never throws (empty on any feed-level failure).
 *
 * <p>Only extracted structured fields leave this class — the raw release prose is never
 * returned or persisted (S&amp;P releases are not public-domain).
 */
@Component
public class SpPressReleaseIndexChangeProvider implements IndexChangeProvider {

    private static final Logger log = LoggerFactory.getLogger(SpPressReleaseIndexChangeProvider.class);

    private static final String SOURCE = "sp_press";
    /** Indices this provider can serve today (title patterns are S&P 500 specific). */
    private static final Set<String> SUPPORTED = Set.of("sp500");

    // Effective date: "...effective prior to the opening of trading on Wednesday, July 15[, 2026]".
    // G1=weekday (anchor only), G2="July 15", G3=optional 4-digit year.
    private static final Pattern EFFECTIVE_DATE = Pattern.compile(
            "(?i)effective (?:prior to|before) the opening of trading on\\s+(\\w+day),\\s+([A-Za-z]+ \\d{1,2})(?:,\\s*(\\d{4}))?");
    // "X (NYSE: AAA) will replace Y (NASD: BBB) in the S&P 500" — G1=add ticker, G2=remove ticker.
    // Exchange labels vary in the prose (NYSE / Nasdaq / NASDAQ / NASD); whitespace around the
    // parens/colon is tolerated because HTML normalisation can leave a stray space.
    private static final Pattern WILL_REPLACE = Pattern.compile(
            "([A-Z]{1,5})\\s*\\)\\s+will replace\\s+.+?\\(\\s*(?:NYSE|Nasdaq|NASDAQ|NASD)\\s*:\\s*([A-Z]{1,5})\\s*\\)");
    // Single deletion without a named replacement — "(NYSE: AAA) will be removed from the S&P 500".
    private static final Pattern SINGLE_DELETION = Pattern.compile(
            "\\(\\s*(?:NYSE|Nasdaq|NASDAQ|NASD)\\s*:\\s*([A-Z]{1,5})\\s*\\)\\s+will be (?:removed|deleted) from");
    // The S&P index a title refers to: "S&P 500" / "S&P MidCap 400" / "S&P SmallCap 600" -> sp500/sp400/sp600.
    private static final Pattern INDEX_IN_TITLE = Pattern.compile(
            "(?i)S&P\\s+(?:SmallCap\\s+|MidCap\\s+)?(\\d{3})");
    private static final DateTimeFormatter EFFECTIVE_FMT =
            DateTimeFormatter.ofPattern("MMMM d yyyy", Locale.ENGLISH);

    // --- HTML normalisation ---
    private static final Pattern HTML_COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern SCRIPT_STYLE = Pattern.compile("(?is)<(script|style)\\b.*?</\\1>");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern ENTITY = Pattern.compile("&(#[xX]?[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);");
    private static final Map<String, String> NAMED_ENTITIES = Map.ofEntries(
            Map.entry("nbsp", " "), Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"),
            Map.entry("quot", "\""), Map.entry("apos", "'"), Map.entry("mdash", "—"),
            Map.entry("ndash", "–"), Map.entry("rsquo", "’"), Map.entry("lsquo", "‘"),
            Map.entry("ldquo", "“"), Map.entry("rdquo", "”"), Map.entry("hellip", "…"));

    private final RestClient http;
    private final String feedPath;
    private final List<Pattern> addTitlePatterns;
    private final List<Pattern> removeTitlePatterns;
    private final TtlCache<String, List<IndexChange>> feedCache;
    private final TtlCache<String, String> detailCache;

    @Autowired
    public SpPressReleaseIndexChangeProvider(
            @Value("${agora.data.sp-press.base-url:https://press.spglobal.com}") String baseUrl,
            @Value("${agora.data.sp-press.feed-path:/index.php?s=2429&l=100&pagetemplate=rss}") String feedPath,
            @Value("${agora.data.sp-press.user-agent:agora/1.0 (research)}") String userAgent,
            @Value("${agora.data.sp-press.add-title-patterns:(?i)^(.+?)\\s+Set to Join S&P 500}")
                    List<String> addTitlePatterns,
            @Value("${agora.data.sp-press.remove-title-patterns:(?i)^(.+?)\\s+(?:Set to be Removed|to be Removed) from S&P 500}")
                    List<String> removeTitlePatterns,
            @Value("${agora.data.cache.ttl.sp-press-seconds:3600}") long ttlSeconds,
            @Value("${agora.data.cache.ttl.sp-press-detail-seconds:604800}") long detailTtlSeconds,
            @Value("${agora.fetch.timeout-ms:15000}") long timeoutMs) {
        this(buildHttp(baseUrl, userAgent, timeoutMs), feedPath, addTitlePatterns, removeTitlePatterns,
                ttlSeconds, detailTtlSeconds, System::currentTimeMillis);
    }

    private static RestClient buildHttp(String baseUrl, String userAgent, long timeoutMs) {
        JdkClientHttpRequestFactory rf = DataHttp.requestFactory(timeoutMs);
        return RestClient.builder()
                .requestFactory(rf)
                .baseUrl(baseUrl)
                .defaultHeader("User-Agent", userAgent)
                .build();
    }

    // Test constructor: pre-built RestClient + config + injectable clock.
    SpPressReleaseIndexChangeProvider(RestClient http, String feedPath, List<String> addTitlePatterns,
            List<String> removeTitlePatterns, long ttlSeconds, long detailTtlSeconds, LongSupplier now) {
        this.http = http;
        this.feedPath = feedPath;
        this.addTitlePatterns = compile(addTitlePatterns);
        this.removeTitlePatterns = compile(removeTitlePatterns);
        // One well-known feed key today; small caps with headroom (detail keyed per release URL).
        this.feedCache = new TtlCache<>(ttlSeconds * 1000L, 8, now);
        this.detailCache = new TtlCache<>(detailTtlSeconds * 1000L, 256, now);
    }

    private static List<Pattern> compile(List<String> patterns) {
        List<Pattern> out = new ArrayList<>();
        if (patterns != null) {
            for (String p : patterns) {
                if (p != null && !p.isBlank()) out.add(Pattern.compile(p.trim()));
            }
        }
        return out;
    }

    @Override
    public int order() { return 10; }

    /** Changes for the given index; empty for any index this provider does not serve. Never throws. */
    @Override
    public List<IndexChange> changes(String index) {
        String normalized = normalizeIndex(index);
        if (!SUPPORTED.contains(normalized)) return List.of();
        List<IndexChange> all;
        try {
            all = feedCache.get("sp_press:feed", this::fetchAndParse);
        } catch (RuntimeException e) {
            log.warn("S&P press-release feed unavailable: {}", e.getMessage());
            return List.of();
        }
        // The feed is parsed once for every index it mentions; return only the requested one so
        // adding MidCap/SmallCap patterns later never misattributes those to sp500.
        List<IndexChange> out = new ArrayList<>();
        for (IndexChange c : all) if (normalized.equals(c.index())) out.add(c);
        return out;
    }

    private static String normalizeIndex(String index) {
        return (index == null || index.isBlank()) ? "sp500" : index.trim().toLowerCase(Locale.ROOT);
    }

    // Throws on feed-level failure so nothing is cached and the next call retries. A zero-change
    // result IS a valid answer (no recent changes) and is cached, unlike constituents. Each item
    // is parsed defensively so one bad item never aborts the whole feed.
    private List<IndexChange> fetchAndParse() {
        String body = http.get().uri(feedPath).retrieve().body(String.class);
        if (body == null || body.isBlank())
            throw new IllegalStateException("empty RSS feed response");
        Document doc = parseXml(body);
        NodeList items = doc.getElementsByTagName("item");
        List<IndexChange> out = new ArrayList<>();
        for (int i = 0; i < items.getLength(); i++) {
            if (!(items.item(i) instanceof Element item)) continue;
            String link = text(item, "link");
            try {
                String title = text(item, "title");
                if (title == null || link == null) continue;
                String action = classify(title);
                if (action == null) continue; // not an index add/remove release
                String index = deriveIndex(title);
                if (index == null) {
                    log.warn("S&P press release: unrecognised index in title, dropping {}", link);
                    continue;
                }
                LocalDate announcement = parsePubDate(text(item, "pubDate"));
                if (announcement == null) {
                    log.warn("S&P press release: unparseable pubDate, dropping {}", link);
                    continue;
                }
                out.addAll(extractFromRelease(link, action, index, announcement));
            } catch (RuntimeException e) {
                log.warn("S&P press release: unexpected error parsing item, dropping {} ({})", link, e.toString());
            }
        }
        return out;
    }

    /** @return "add"/"remove" if a title pattern matches, else null. Package-private for tests. */
    String classify(String title) {
        for (Pattern p : addTitlePatterns) if (p.matcher(title).find()) return "add";
        for (Pattern p : removeTitlePatterns) if (p.matcher(title).find()) return "remove";
        return null;
    }

    /** The index label ("sp500"/"sp400"/"sp600") named in the title, or null. Package-private for tests. */
    static String deriveIndex(String title) {
        if (title == null) return null;
        Matcher m = INDEX_IN_TITLE.matcher(title);
        return m.find() ? "sp" + m.group(1) : null;
    }

    // Fail-soft per item: fetch the release body, normalise to plain text, and extract structured
    // fields. A body failure or a missing ticker/effective-date drops the item (WARN + URL).
    private List<IndexChange> extractFromRelease(String link, String titleAction, String index, LocalDate announcement) {
        String raw;
        try {
            raw = detailCache.get(link, () -> {
                String b = http.get().uri(URI.create(link)).retrieve().body(String.class);
                if (b == null) throw new IllegalStateException("empty release body");
                return b;
            });
        } catch (RuntimeException e) {
            log.warn("S&P press release: body fetch failed, dropping {} ({})", link, e.getMessage());
            return List.of();
        }

        String text = normalizeHtml(raw);
        LocalDate effective = parseEffectiveDate(text, announcement);
        if (effective == null) {
            log.warn("S&P press release: no effective date, dropping {}", link);
            return List.of();
        }

        Matcher replace = WILL_REPLACE.matcher(text);
        if (replace.find()) {
            // A "will replace" release documents both sides — emit the add and the remove.
            return List.of(
                    new IndexChange(replace.group(1), "add", index, announcement, effective, SOURCE),
                    new IndexChange(replace.group(2), "remove", index, announcement, effective, SOURCE));
        }
        Matcher deletion = SINGLE_DELETION.matcher(text);
        if (deletion.find()) {
            return List.of(new IndexChange(deletion.group(1), titleAction, index, announcement, effective, SOURCE));
        }
        log.warn("S&P press release: title matched but no ticker found, dropping {}", link);
        return List.of();
    }

    // Package-private for the year-inference unit test.
    static LocalDate parseEffectiveDate(String body, LocalDate announcement) {
        Matcher m = EFFECTIVE_DATE.matcher(body);
        if (!m.find()) return null;
        String monthDay = m.group(2); // e.g. "July 15"
        String yearGroup = m.group(3);
        try {
            if (yearGroup != null) {
                return LocalDate.parse(monthDay + " " + yearGroup, EFFECTIVE_FMT);
            }
            // Infer the year from the announcement date; effective is always on/after the
            // announcement, so a candidate that lands before it rolled over a year boundary
            // (Dec announcement -> Jan effective).
            LocalDate candidate = LocalDate.parse(monthDay + " " + announcement.getYear(), EFFECTIVE_FMT);
            if (candidate.isBefore(announcement)) candidate = candidate.plusYears(1);
            return candidate;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static LocalDate parsePubDate(String pubDate) {
        if (pubDate == null || pubDate.isBlank()) return null;
        try {
            return ZonedDateTime.parse(pubDate.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDate();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Flattens a press-release HTML body to plain text before regex matching: strips comments,
     * script/style blocks and tags, decodes HTML entities (named + numeric, {@code &nbsp;} ->
     * space), and collapses runs of whitespace to a single space. Package-private for tests.
     */
    static String normalizeHtml(String html) {
        if (html == null) return "";
        String s = HTML_COMMENT.matcher(html).replaceAll(" ");
        s = SCRIPT_STYLE.matcher(s).replaceAll(" ");
        s = HTML_TAG.matcher(s).replaceAll(" ");
        s = decodeEntities(s);
        return WHITESPACE.matcher(s).replaceAll(" ").trim();
    }

    private static String decodeEntities(String s) {
        Matcher m = ENTITY.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String ent = m.group(1);
            String rep;
            if (ent.charAt(0) == '#') {
                String num = ent.substring(1);
                int radix = 10;
                if (num.startsWith("x") || num.startsWith("X")) { radix = 16; num = num.substring(1); }
                try {
                    rep = new String(Character.toChars(Integer.parseInt(num, radix)));
                } catch (RuntimeException e) {
                    rep = m.group(0); // leave malformed numeric entity untouched
                }
            } else {
                rep = NAMED_ENTITIES.getOrDefault(ent.toLowerCase(Locale.ROOT), m.group(0));
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String text(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            // Guard against nested elements sharing the tag name in other namespaces; the direct
            // child is the RSS field we want.
            if (n.getParentNode() == parent) {
                String t = n.getTextContent();
                return t == null ? null : t.trim();
            }
        }
        return null;
    }

    private static Document parseXml(String xml) {
        try {
            return newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("RSS parse failed: " + e.getMessage(), e);
        }
    }

    // Secure XXE-off parser, mirroring EdgarSearchService. The RSS envelope is well-formed;
    // external entities / DTD loading are disabled as defence in depth.
    private static DocumentBuilder newDocumentBuilder() {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        try {
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (ParserConfigurationException ignored) { /* best effort */ }
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);
        try {
            return dbf.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("failed to configure RSS XML parser", e);
        }
    }
}
