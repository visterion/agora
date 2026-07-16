package de.visterion.agora.fetch.news;

import de.visterion.agora.data.DataHttp;
import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.ProviderErrors;
import de.visterion.agora.data.TtlCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Generic RSS 2.0 / Atom company-news provider. Exactly one class, but one INSTANCE per
 * configured feed (registered by NewsConfiguration from NewsFeedsProperties), so per-feed
 * warnings, per-feed timeout, and parallel fan-out need no special path in the aggregator.
 *
 * <p>Parsing uses only the JDK XML stack (no external dependency), hardened against
 * XXE/entity expansion (secure processing, DOCTYPE rejected, no external entities).
 * Unparseable XML degrades to an empty list (logged); transport/HTTP failures throw
 * {@link MarketDataException} with a sanitized {@link ProviderErrors} message.
 */
public class RssNewsProvider implements NewsProvider {

    private static final Logger log = LoggerFactory.getLogger(RssNewsProvider.class);
    private static final String USER_AGENT = "agora-news/1.0";
    private static final String SYMBOL_PLACEHOLDER = "{symbol}";

    // DocumentBuilderFactory/DocumentBuilder are not guaranteed thread-safe (JAXP). One builder
    // per thread avoids concurrent parse() corruption under the aggregator's parallel fan-out.
    private static final ThreadLocal<DocumentBuilder> DOC_BUILDER =
            ThreadLocal.withInitial(RssNewsProvider::newDocumentBuilder);

    private final String feedId;
    private final String urlTemplate;
    private final String sourceType;
    private final RestClient http;
    private final TtlCache<String, List<NewsItem>> cache;

    public RssNewsProvider(String feedId, String urlTemplate, String sourceType,
                           long feedTimeoutMs, long ttlSeconds, LongSupplier now) {
        this.feedId = feedId;
        this.urlTemplate = urlTemplate;
        this.sourceType = sourceType;
        // DataHttp.clientBuilder wires the ProviderCallLogger interceptor (structured
        // provider_call logging + redaction) exactly like the market-data providers.
        this.http = DataHttp.clientBuilder(feedTimeoutMs)
                .defaultHeader("User-Agent", USER_AGENT)
                .build();
        // Keyed by feedId+symbol+date-range; the TTL is the request-rate defence for
        // anonymously rate-limited feed hosts.
        this.cache = new TtlCache<>(ttlSeconds * 1000L, 2048, now);
    }

    @Override
    public String id() { return "rss:" + feedId; }

    @Override
    public boolean configured() {
        return feedId != null && !feedId.isBlank() && urlTemplate != null && !urlTemplate.isBlank();
    }

    @Override
    public List<NewsItem> companyNews(String symbol, LocalDate from, LocalDate to) {
        return cache.get(feedId + ":" + symbol + ":" + from + ":" + to, () -> fetch(symbol, from, to));
    }

    private List<NewsItem> fetch(String symbol, LocalDate from, LocalDate to) {
        boolean templated = urlTemplate.contains(SYMBOL_PLACEHOLDER);
        String url = templated
                ? urlTemplate.replace(SYMBOL_PLACEHOLDER, URLEncoder.encode(symbol, StandardCharsets.UTF_8))
                : urlTemplate;
        String xml;
        try {
            xml = http.get().uri(URI.create(url)).retrieve().body(String.class);
        } catch (Exception e) {
            log.warn("rss feed {} request failed for {}", feedId, symbol, e);
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    ProviderErrors.categorize(id(), e), e);
        }
        if (xml == null || xml.isBlank())
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    id() + " empty feed body", null);
        List<NewsItem> items = parse(xml);
        if (!templated) items = filterBySymbolToken(items, symbol);
        return filterByWindow(items, from, to);
    }

    // ---- parsing ----

    private List<NewsItem> parse(String xml) {
        Document doc;
        try {
            doc = DOC_BUILDER.get().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            log.warn("rss feed {} returned unparseable XML: {}", feedId, e.toString());
            return List.of();
        }
        String root = doc.getDocumentElement().getTagName();
        if ("rss".equalsIgnoreCase(root)) return parseRss(doc);
        if ("feed".equalsIgnoreCase(root)) return parseAtom(doc);
        log.warn("rss feed {} has unrecognized root element <{}>", feedId, root);
        return List.of();
    }

    private List<NewsItem> parseRss(Document doc) {
        List<NewsItem> out = new ArrayList<>();
        NodeList items = doc.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String title = text(item, "title");
            if (title.isBlank()) {
                log.warn("rss feed {}: skipping item without title", feedId);
                continue;
            }
            out.add(new NewsItem(
                    title,
                    stripHtml(text(item, "description")),
                    feedId,
                    sourceType,
                    parseRfc1123(text(item, "pubDate")),
                    text(item, "link")));
        }
        return out;
    }

    private List<NewsItem> parseAtom(Document doc) {
        List<NewsItem> out = new ArrayList<>();
        NodeList entries = doc.getElementsByTagName("entry");
        for (int i = 0; i < entries.getLength(); i++) {
            Element entry = (Element) entries.item(i);
            String title = text(entry, "title");
            if (title.isBlank()) {
                log.warn("rss feed {}: skipping entry without title", feedId);
                continue;
            }
            String summary = text(entry, "content");
            if (summary.isBlank()) summary = text(entry, "summary");
            out.add(new NewsItem(
                    title,
                    stripHtml(summary),
                    feedId,
                    sourceType,
                    parseIsoOffset(text(entry, "updated")),
                    atomLink(entry)));
        }
        return out;
    }

    /** First {@code <link>} href with rel absent or "alternate"; fallback: first link's href. */
    private static String atomLink(Element entry) {
        NodeList links = entry.getElementsByTagName("link");
        String fallback = "";
        for (int i = 0; i < links.getLength(); i++) {
            Element link = (Element) links.item(i);
            String href = link.getAttribute("href");
            if (href.isBlank()) continue;
            if (fallback.isEmpty()) fallback = href;
            String rel = link.getAttribute("rel");
            if (rel.isBlank() || "alternate".equals(rel)) return href;
        }
        return fallback;
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) return "";
        String v = nodes.item(0).getTextContent();
        return v == null ? "" : v.trim();
    }

    private Instant parseRfc1123(String s) {
        if (s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (Exception e) {
            log.warn("rss feed {}: unparseable pubDate '{}'", feedId, s);
            return null;
        }
    }

    private Instant parseIsoOffset(String s) {
        if (s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (Exception e) {
            log.warn("rss feed {}: unparseable updated '{}'", feedId, s);
            return null;
        }
    }

    /** Strips HTML tags, decodes common entities, collapses whitespace — plain text summaries only. */
    static String stripHtml(String s) {
        if (s == null || s.isEmpty()) return "";
        String noTags = s.replaceAll("(?s)<[^>]*>", " ");
        String decoded = noTags
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replace("&nbsp;", " ").replace("&amp;", "&");
        return decoded.replaceAll("\\s+", " ").trim();
    }

    // ---- filtering ----

    /** Title word-token match for generic feeds without a {symbol} placeholder. Never the raw
     *  symbol in the regex — Pattern.quote keeps metacharacter symbols (BRK.B, ^GSPC) literal. */
    private static List<NewsItem> filterBySymbolToken(List<NewsItem> items, String symbol) {
        Pattern p = Pattern.compile("(?<!\\w)" + Pattern.quote(symbol) + "(?!\\w)",
                Pattern.CASE_INSENSITIVE);
        return items.stream().filter(n -> p.matcher(n.headline()).find()).toList();
    }

    /** UTC window [from 00:00, to+1 00:00); dateless items pass any window. */
    private static List<NewsItem> filterByWindow(List<NewsItem> items, LocalDate from, LocalDate to) {
        Instant start = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<NewsItem> out = new ArrayList<>();
        for (NewsItem n : items) {
            if (n.datetime() == null
                    || (!n.datetime().isBefore(start) && n.datetime().isBefore(end))) {
                out.add(n);
            }
        }
        return out;
    }

    // ---- hardened parser ----

    private static DocumentBuilder newDocumentBuilder() {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        try {
            // Secure processing caps entity expansion (billion-laughs); DOCTYPE is rejected
            // outright — feeds never legitimately carry one, unlike EDGAR Form 4 XML.
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("failed to configure secure feed XML parser", e);
        }
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);
        try {
            return dbf.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("failed to create feed XML parser", e);
        }
    }
}
