package de.visterion.agora.fetch.edgar;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.TtlCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
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
    private final TtlCache<String, CompanyFacts> factsCache;

    /** An XBRL company-concept series: the reporting {@code unit} (e.g. "USD") plus its datapoints.
     *  unit is null and datapoints empty when the concept does not exist for the company. */
    public record ConceptSeries(String unit, List<ConceptDatapoint> datapoints) {}

    /** All us-gaap concepts for a company from a single companyfacts fetch, keyed by tag. */
    public record CompanyFacts(Map<String, ConceptSeries> byTag) {
        public ConceptSeries series(String tag) {
            return byTag.getOrDefault(tag, new ConceptSeries(null, List.of()));
        }
        public boolean isEmpty() {
            return byTag.isEmpty();
        }
    }

    @Autowired
    public EdgarService(@Value("${agora.data.edgar.user-agent}") String userAgent,
                        EdgarCikResolver cikResolver,
                        @Value("${agora.data.cache.ttl.filings-seconds:3600}") long ttlSeconds,
                        @Value("${agora.fetch.timeout-ms:15000}") long timeoutMs) {
        this(buildHttp(userAgent, timeoutMs), cikResolver, ttlSeconds, System::currentTimeMillis);
    }

    private static RestClient buildHttp(String userAgent, long timeoutMs) {
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory();
        rf.setReadTimeout(Duration.ofMillis(timeoutMs));
        return RestClient.builder()
                .requestFactory(rf)
                .baseUrl("https://data.sec.gov")
                .defaultHeader("User-Agent", userAgent)
                .build();
    }

    // protected (not package-private) so a cross-package @Primary stub subclass in an
    // integration test can invoke it via super(...); still not part of the public API.
    protected EdgarService(RestClient http, EdgarCikResolver cikResolver, long ttlSeconds, LongSupplier now) {
        this.http = http;
        this.cikResolver = cikResolver;
        this.filingsCache = new TtlCache<>(ttlSeconds * 1000L, 1024, now);
        this.epsCache = new TtlCache<>(ttlSeconds * 1000L, 1024, now);
        this.conceptCache = new TtlCache<>(ttlSeconds * 1000L, 512, now);
        // Company-facts bodies are full XBRL dumps per CIK — multi-MB each — keep this cache small.
        this.factsCache = new TtlCache<>(ttlSeconds * 1000L, 64, now);
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
        long cikNum = Long.parseLong(padded);
        JsonNode filings = body.path("filings");
        List<FilingRef> out = new ArrayList<>();
        appendFilings(out, filings.path("recent"), formType, from, to, limit, cikNum);

        // filings.recent covers only the most recent ~1000 filings; older filings live in
        // archive pages referenced by filings.files. Only bother fetching them when the
        // window (or limit) isn't already satisfied by `recent`.
        if (out.size() < limit && filings.path("files").isArray()) {
            List<JsonNode> archives = new ArrayList<>();
            filings.path("files").forEach(archives::add);
            archives.sort(Comparator.comparing(f -> parseDateOrMin(f.path("filingFrom").asString(""))));
            for (JsonNode archive : archives) {
                if (out.size() >= limit) break;
                LocalDate filingFrom = parseDate(archive.path("filingFrom").asString(""));
                LocalDate filingTo = parseDate(archive.path("filingTo").asString(""));
                if (to != null && filingFrom != null && filingFrom.isAfter(to)) continue;
                if (from != null && filingTo != null && filingTo.isBefore(from)) continue;
                String name = archive.path("name").asString("");
                if (name.isBlank()) continue;
                JsonNode archiveBody;
                try {
                    archiveBody = http.get().uri("/submissions/{name}", name).retrieve().body(JsonNode.class);
                } catch (RestClientResponseException e) {
                    throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "EDGAR HTTP " + e.getStatusCode(), e);
                } catch (Exception e) {
                    throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "EDGAR unreachable: " + e.getMessage(), e);
                }
                if (archiveBody != null) appendFilings(out, archiveBody, formType, from, to, limit, cikNum);
            }
        }
        return out;
    }

    /** Parses one submissions-shaped node (either filings.recent or an archive page) and appends
     *  matching FilingRefs to out, subject to formType/from/to/limit. */
    private static void appendFilings(List<FilingRef> out, JsonNode r, String formType, LocalDate from,
                                       LocalDate to, int limit, long cikNum) {
        JsonNode acc = r.path("accessionNumber"), forms = r.path("form"), filed = r.path("filingDate"),
                 report = r.path("reportDate"), docs = r.path("primaryDocument");
        if (!acc.isArray()) return;
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
            // An empty primaryDocument means EDGAR has no single-document link for this
            // filing; skip building a (misleading, doc-less) directory-index URL.
            String url = doc.isBlank() ? null
                    : "https://www.sec.gov/Archives/edgar/data/" + cikNum + "/" + noDash + "/" + doc;
            out.add(new FilingRef(accession, form, filedDate, reportDate, doc, url));
        }
    }

    private static LocalDate parseDateOrMin(String s) {
        LocalDate d = parseDate(s);
        return d != null ? d : LocalDate.MIN;
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
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) return List.of();   // genuine "tag not filed" — cacheable empty
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "EDGAR HTTP " + e.getStatusCode(), e);
        } catch (Exception e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "EDGAR unreachable: " + e.getMessage(), e);
        }
        if (body == null) return List.of();

        JsonNode units = body.path("units");
        String unit = selectPreferredUnit(units);
        if (unit == null) return List.of();

        Map<LocalDate, EpsPoint> byEnd = new LinkedHashMap<>();
        for (JsonNode row : units.path(unit)) {           // iterate the ONE selected unit's array only — never merge units
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
                // quarterly fact and a 6-/9-month YTD fact, or an original vs. a
                // restated/amended fact for the identical period). Prefer the SHORTEST
                // duration so the quarterly fact always wins regardless of emission order;
                // when durations tie (e.g. a restatement of the same period), prefer the
                // LATEST filed date so restated/amended values win over the original.
                EpsPoint existing = byEnd.get(periodEnd);
                if (existing == null) {
                    byEnd.put(periodEnd, candidate);
                } else {
                    long candidateDays = durationDays(candidate), existingDays = durationDays(existing);
                    if (candidateDays < existingDays) {
                        byEnd.put(periodEnd, candidate);
                    } else if (candidateDays == existingDays
                            && candidate.filed() != null
                            && (existing.filed() == null || candidate.filed().isAfter(existing.filed()))) {
                        byEnd.put(periodEnd, candidate);
                    }
                }
            } catch (Exception rowError) {
                // skip malformed row, keep the rest
            }
        }
        return deriveQuarterlySeries(byEnd.values());
    }

    /** Selects ONE unit key from a companyconcept/companyfacts "units" object — never merges
     *  multiple units (which would silently mix currencies, e.g. USD and EUR EPS values).
     *  Prefers USD or USD/shares when present; otherwise the unit with the most datapoints. */
    private static String selectPreferredUnit(JsonNode units) {
        if (units.has("USD")) return "USD";
        if (units.has("USD/shares")) return "USD/shares";
        String best = null;
        int bestSize = -1;
        var names = units.propertyNames().iterator();
        while (names.hasNext()) {
            String candidate = names.next();
            int size = units.path(candidate).size();
            if (size > bestSize) {
                bestSize = size;
                best = candidate;
            }
        }
        return best;
    }

    /** True if the datapoint spans a fiscal-year-length duration (~350-380 days) or is
     *  explicitly tagged fp="FY" — these are annual facts, not quarterly ones. */
    private static boolean isAnnual(EpsPoint p) {
        if ("FY".equals(p.fiscalPeriod())) return true;
        if (p.periodStart() == null) return false;
        long days = java.time.temporal.ChronoUnit.DAYS.between(p.periodStart(), p.periodEnd());
        return days >= 350 && days <= 380;
    }

    /** Builds the quarterly EPS series from deduped datapoints: FY frames are excluded from
     *  the quarterly output; when a fiscal year has Q1+Q2+Q3 but no reported Q4, Q4 is derived
     *  as FY - (Q1+Q2+Q3) and flagged {@code derived=true}; a fiscal year with an FY fact but
     *  no quarterly facts at all is omitted entirely (nothing to derive from). */
    private static List<EpsPoint> deriveQuarterlySeries(Collection<EpsPoint> points) {
        Map<Integer, EpsPoint> fyByYear = new LinkedHashMap<>();
        Map<Integer, Map<String, EpsPoint>> quartersByYear = new LinkedHashMap<>();
        List<EpsPoint> out = new ArrayList<>();
        for (EpsPoint p : points) {
            if (isAnnual(p)) {
                if (p.fiscalYear() != null) fyByYear.put(p.fiscalYear(), p);
                continue;                                  // FY frames never appear in the quarterly series
            }
            out.add(p);
            if (p.fiscalYear() != null && p.fiscalPeriod() != null) {
                quartersByYear.computeIfAbsent(p.fiscalYear(), k -> new LinkedHashMap<>()).put(p.fiscalPeriod(), p);
            }
        }
        for (Map.Entry<Integer, EpsPoint> entry : fyByYear.entrySet()) {
            Integer fy = entry.getKey();
            EpsPoint fyPoint = entry.getValue();
            Map<String, EpsPoint> quarters = quartersByYear.get(fy);
            if (quarters == null || quarters.containsKey("Q4")) continue;
            EpsPoint q1 = quarters.get("Q1"), q2 = quarters.get("Q2"), q3 = quarters.get("Q3");
            if (q1 == null || q2 == null || q3 == null) continue;
            if (q1.value() == null || q2.value() == null || q3.value() == null || fyPoint.value() == null) continue;
            java.math.BigDecimal q4Value = fyPoint.value().subtract(q1.value()).subtract(q2.value()).subtract(q3.value());
            out.add(new EpsPoint(fyPoint.periodEnd(), null, q4Value, fy, "Q4", fyPoint.form(), fyPoint.filed(), true));
        }
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
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) return new ConceptSeries(null, List.of());   // genuine "concept not filed" — cacheable empty
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "EDGAR HTTP " + e.getStatusCode(), e);
        } catch (Exception e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "EDGAR unreachable: " + e.getMessage(), e);
        }
        if (body == null) return new ConceptSeries(null, List.of());

        List<ConceptDatapoint> out = new ArrayList<>();
        JsonNode units = body.path("units");
        String unit = selectPreferredUnit(units);
        if (unit != null) {
            for (JsonNode row : units.path(unit)) {         // the ONE selected unit only — never merge units
                try {
                    out.add(parseDatapoint(row));
                } catch (Exception rowError) {
                    // skip malformed row, keep the rest
                }
            }
        }
        out.sort(Comparator.comparing(ConceptDatapoint::periodEnd).reversed());
        return new ConceptSeries(unit, out);
    }

    /** All us-gaap concepts for a company (by symbol or CIK) from a single companyfacts fetch.
     *  Cheaper than N calls to {@link #companyConcept} when several tags are needed for one company.
     *  A 404 (company has no XBRL facts on file) yields an empty CompanyFacts; any other
     *  failure (429/5xx/timeout) throws so nothing is cached. */
    public CompanyFacts companyFacts(String symbol, String cik) {
        String padded = resolveCik(symbol, cik);
        return factsCache.get("facts:" + padded, () -> fetchCompanyFacts(padded));
    }

    private CompanyFacts fetchCompanyFacts(String padded) {
        JsonNode body;
        try {
            String raw = http.get()
                    .uri("/api/xbrl/companyfacts/CIK{cik}.json", padded)
                    .retrieve().body(String.class);
            body = raw == null ? null : EPS_MAPPER.readTree(raw);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) return new CompanyFacts(Map.of());   // genuine "no facts filed" — cacheable empty
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "EDGAR HTTP " + e.getStatusCode(), e);
        } catch (Exception e) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "EDGAR unreachable: " + e.getMessage(), e);
        }
        if (body == null) return new CompanyFacts(Map.of());

        Map<String, ConceptSeries> byTag = new LinkedHashMap<>();
        JsonNode usGaap = body.path("facts").path("us-gaap");
        var tagNames = usGaap.propertyNames().iterator();
        while (tagNames.hasNext()) {
            String tag = tagNames.next();
            JsonNode units = usGaap.path(tag).path("units");
            List<ConceptDatapoint> out = new ArrayList<>();
            String unit = selectPreferredUnit(units);
            if (unit != null) {
                for (JsonNode row : units.path(unit)) {     // the ONE selected unit only — never merge units
                    try {
                        out.add(parseDatapoint(row));
                    } catch (Exception rowError) {
                        // skip malformed row, keep the rest
                    }
                }
            }
            out.sort(Comparator.comparing(ConceptDatapoint::periodEnd).reversed());
            byTag.put(tag, new ConceptSeries(unit, out));
        }
        return new CompanyFacts(byTag);
    }

    private static ConceptDatapoint parseDatapoint(JsonNode row) {
        String start = row.path("start").asString("");
        String end = row.path("end").asString("");
        if (end.isEmpty() || row.path("val").isMissingNode())
            throw new IllegalArgumentException("missing end/val");
        LocalDate periodEnd = LocalDate.parse(end);
        LocalDate periodStart = start.isEmpty() ? null : LocalDate.parse(start);
        Integer fy = row.path("fy").isMissingNode() || row.path("fy").isNull()
                ? null : row.path("fy").asInt();
        String fp = row.path("fp").asString(null);
        String form = row.path("form").asString(null);
        LocalDate filed = parseDate(row.path("filed").asString(""));
        return new ConceptDatapoint(periodStart, periodEnd,
                row.path("val").decimalValue(), fy, fp, form, filed);
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.length() < 10) return null;
        try { return LocalDate.parse(s.substring(0, 10)); } catch (Exception e) { return null; }
    }
}
