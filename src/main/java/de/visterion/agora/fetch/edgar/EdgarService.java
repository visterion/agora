package de.visterion.agora.fetch.edgar;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.TtlCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/** SEC EDGAR fetch: recent filings (submissions API) and diluted/basic EPS history (companyconcept). */
@Component
public class EdgarService {

    private static final String[] EPS_TAGS = {"EarningsPerShareDiluted", "EarningsPerShareBasic"};

    // Parse JSON floats as BigDecimal so reported EPS scale (e.g. "2.40") is preserved exactly.
    private static final JsonMapper EPS_MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private final RestClient http;
    private final EdgarCikResolver cikResolver;
    private final TtlCache<String, List<FilingRef>> filingsCache;
    private final TtlCache<String, List<EpsPoint>> epsCache;
    private final TtlCache<String, ConceptSeries> conceptCache;

    /** An XBRL company-concept series: the reporting {@code unit} (e.g. "USD") plus its datapoints.
     *  unit is null and datapoints empty when the concept does not exist for the company. */
    public record ConceptSeries(String unit, List<ConceptDatapoint> datapoints) {}

    @Autowired
    public EdgarService(@Value("${agora.data.edgar.user-agent}") String userAgent,
                        EdgarCikResolver cikResolver,
                        @Value("${agora.data.cache.ttl.filings-seconds:3600}") long ttlSeconds) {
        this(RestClient.builder().baseUrl("https://data.sec.gov").defaultHeader("User-Agent", userAgent).build(),
                cikResolver, ttlSeconds, System::currentTimeMillis);
    }

    // protected (not package-private) so a cross-package @Primary stub subclass in an
    // integration test can invoke it via super(...); still not part of the public API.
    protected EdgarService(RestClient http, EdgarCikResolver cikResolver, long ttlSeconds, LongSupplier now) {
        this.http = http;
        this.cikResolver = cikResolver;
        this.filingsCache = new TtlCache<>(ttlSeconds * 1000L, now);
        this.epsCache = new TtlCache<>(ttlSeconds * 1000L, now);
        this.conceptCache = new TtlCache<>(ttlSeconds * 1000L, now);
    }

    /** cikOrSymbol: 10-digit CIK if all-digits, else resolved via ticker. */
    public String resolveCik(String symbol, String cik) {
        if (cik != null && !cik.isBlank()) {
            try {
                return String.format("%010d", Long.parseLong(cik.trim()));
            } catch (NumberFormatException e) {
                throw new MarketDataException(MarketDataException.Kind.NOT_FOUND, "invalid CIK (must be numeric): " + cik, e);
            }
        }
        Optional<String> resolved = cikResolver.cik(symbol);
        if (resolved.isEmpty())
            throw new MarketDataException(MarketDataException.Kind.NOT_FOUND, "no CIK for " + symbol, null);
        return resolved.get();
    }

    public List<FilingRef> filings(String symbol, String cik, String formType, LocalDate from, LocalDate to, int limit) {
        String padded = resolveCik(symbol, cik);
        String key = "filings:" + padded + ":" + formType + ":" + from + ":" + to + ":" + limit;
        return filingsCache.get(key, () -> fetchFilings(padded, formType, from, to, limit));
    }

    private List<FilingRef> fetchFilings(String padded, String formType, LocalDate from, LocalDate to, int limit) {
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
                if (to != null && filedDate.isAfter(to)) continue;
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

    public List<EpsPoint> epsHistory(String symbol, String cik) {
        String padded = resolveCik(symbol, cik);
        return epsCache.get("eps:" + padded, () -> fetchEps(padded));
    }

    private List<EpsPoint> fetchEps(String padded) {
        List<EpsPoint> firstNonEmpty = null;
        for (String tag : EPS_TAGS) {
            List<EpsPoint> points = fetchConcept(padded, tag);
            if (points.isEmpty()) continue;
            if (firstNonEmpty == null) firstNonEmpty = points;
            // Prefer a tag with genuine quarterly facts; a concept carrying only
            // annual/YTD facts (e.g. Diluted reported solely on a 10-K) must fall
            // back to the next tag even though its raw series is non-empty.
            if (hasQuarterly(points)) return points;
        }
        return firstNonEmpty != null ? firstNonEmpty : List.of();
    }

    /** Duration in days for period-end dedup preference; null periodStart (instant facts) is
     *  treated as longest/least-preferred since EPS facts are durations, not instants. */
    private static long durationDays(EpsPoint p) {
        if (p.periodStart() == null) return Long.MAX_VALUE;
        return java.time.temporal.ChronoUnit.DAYS.between(p.periodStart(), p.periodEnd());
    }

    /** True if any point's period spans roughly one fiscal quarter (80-100 days, inclusive). */
    private static boolean hasQuarterly(List<EpsPoint> points) {
        for (EpsPoint p : points) {
            if (p.periodStart() == null) continue;
            long days = java.time.temporal.ChronoUnit.DAYS.between(p.periodStart(), p.periodEnd());
            if (days >= 80 && days <= 100) return true;
        }
        return false;
    }

    private List<EpsPoint> fetchConcept(String padded, String tag) {
        JsonNode body;
        try {
            String raw = http.get()
                    .uri("/api/xbrl/companyconcept/CIK{cik}/us-gaap/{tag}.json", padded, tag)
                    .retrieve().body(String.class);
            body = raw == null ? null : EPS_MAPPER.readTree(raw);
        } catch (Exception e) {
            return List.of();   // 404/error on a tag → treat as empty, try the next tag
        }
        if (body == null) return List.of();

        Map<LocalDate, EpsPoint> byEnd = new LinkedHashMap<>();
        for (JsonNode unit : body.path("units")) {        // iterate unit arrays (e.g. "USD/shares")
            for (JsonNode row : unit) {
                try {
                    String start = row.path("start").asString("");
                    String end = row.path("end").asString("");
                    if (end.isEmpty() || row.path("val").isMissingNode()) continue;
                    LocalDate periodEnd = LocalDate.parse(end);
                    LocalDate periodStart = start.isEmpty() ? null : LocalDate.parse(start);
                    Integer fy = row.path("fy").isMissingNode() || row.path("fy").isNull()
                            ? null : row.path("fy").asInt();
                    String fp = row.path("fp").asString(null);
                    String form = row.path("form").asString(null);
                    LocalDate filed = parseDate(row.path("filed").asString(""));
                    EpsPoint candidate = new EpsPoint(periodEnd, periodStart,
                            row.path("val").decimalValue(), fy, fp, form, filed);
                    // EDGAR may emit multiple facts sharing a period-end (e.g. a 3-month
                    // quarterly fact and a 6-/9-month YTD fact). Prefer the SHORTEST
                    // duration so the quarterly fact always wins regardless of emission order.
                    EpsPoint existing = byEnd.get(periodEnd);
                    if (existing == null || durationDays(candidate) < durationDays(existing)) {
                        byEnd.put(periodEnd, candidate);
                    }
                } catch (Exception rowError) {
                    // skip malformed row, keep the rest
                }
            }
        }
        List<EpsPoint> out = new ArrayList<>(byEnd.values());
        out.sort(Comparator.comparing(EpsPoint::periodEnd).reversed());
        return out;
    }

    /** Any XBRL company-concept for a company (by symbol or CIK), e.g. us-gaap/Assets.
     *  Returns all datapoints (no quarterly filter), sorted by periodEnd descending.
     *  A concept absent for the company (404) yields an empty series rather than an error. */
    public ConceptSeries companyConcept(String symbol, String cik, String taxonomy, String tag) {
        String padded = resolveCik(symbol, cik);
        String tax = (taxonomy == null || taxonomy.isBlank()) ? "us-gaap" : taxonomy.trim();
        String key = "concept:" + padded + ":" + tax + ":" + tag;
        return conceptCache.get(key, () -> fetchCompanyConcept(padded, tax, tag));
    }

    private ConceptSeries fetchCompanyConcept(String padded, String taxonomy, String tag) {
        JsonNode body;
        try {
            String raw = http.get()
                    .uri("/api/xbrl/companyconcept/CIK{cik}/{taxonomy}/{tag}.json", padded, taxonomy, tag)
                    .retrieve().body(String.class);
            body = raw == null ? null : EPS_MAPPER.readTree(raw);
        } catch (Exception e) {
            return new ConceptSeries(null, List.of());   // 404/error → concept may not exist; not fatal
        }
        if (body == null) return new ConceptSeries(null, List.of());

        String unit = null;
        List<ConceptDatapoint> out = new ArrayList<>();
        JsonNode units = body.path("units");
        var fields = units.propertyNames().iterator();
        if (fields.hasNext()) {
            unit = fields.next();                          // take the FIRST unit field name
            for (JsonNode row : units.path(unit)) {
                try {
                    String start = row.path("start").asString("");
                    String end = row.path("end").asString("");
                    if (end.isEmpty() || row.path("val").isMissingNode()) continue;
                    LocalDate periodEnd = LocalDate.parse(end);
                    LocalDate periodStart = start.isEmpty() ? null : LocalDate.parse(start);
                    Integer fy = row.path("fy").isMissingNode() || row.path("fy").isNull()
                            ? null : row.path("fy").asInt();
                    String fp = row.path("fp").asString(null);
                    String form = row.path("form").asString(null);
                    LocalDate filed = parseDate(row.path("filed").asString(""));
                    out.add(new ConceptDatapoint(periodStart, periodEnd,
                            row.path("val").decimalValue(), fy, fp, form, filed));
                } catch (Exception rowError) {
                    // skip malformed row, keep the rest
                }
            }
        }
        out.sort(Comparator.comparing(ConceptDatapoint::periodEnd).reversed());
        return new ConceptSeries(unit, out);
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.length() < 10) return null;
        try { return LocalDate.parse(s.substring(0, 10)); } catch (Exception e) { return null; }
    }
}
