package de.visterion.agora.fetch.reference.change;

import de.visterion.agora.data.DataHttp;
import de.visterion.agora.data.TtlCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Index constituent changes derived from FTSE Russell's annual US reconstitution. The Russell
 * 3000 additions/deletions are published as free, per-date-immutable PDFs (stable URL pattern);
 * the Russell 1000 vs. Russell 2000 split is not in those PDFs, so the target index bucket is
 * resolved against the iShares IWB (Russell 1000) / IWM (Russell 2000) holdings CSVs.
 *
 * <p>Two independently degradable sub-fetches, both fail-soft (never throw):
 * <ol>
 *   <li><b>Schedule</b> — {@link RussellReconstitutionCalendar}, config-driven (not scraped):
 *       tells which dated PDFs to fetch and supplies announcement (preliminary) / effective dates.
 *       This is deterministic, so it works even when both network fetches fail.</li>
 *   <li><b>Reconstitution PDFs</b> — the {@code ru3000-additions}/{@code ru3000-deletions} lists
 *       for the active window (final effective-dated list preferred, preliminary-dated list as a
 *       fallback while the final is unpublished). {@link PdfTextExtractor} + a text parse; any
 *       failure yields an empty list for that side.</li>
 *   <li><b>iShares holdings</b> — IWB/IWM CSV ticker sets used only to resolve the R1000/R2000
 *       bucket. The endpoint is bot-walled from server IPs (returns an HTML product page rather
 *       than CSV); a non-CSV / unreachable response degrades to an empty set.</li>
 * </ol>
 *
 * <p><b>Bucket resolution + default assumption:</b> an add/remove ticker present in IWB holdings
 * resolves to {@code russell1000}, in IWM holdings to {@code russell2000}. When iShares cannot
 * resolve it (unreachable, walled, or the ticker is absent — always true for a deletion, since the
 * name has already left the index) the bucket defaults to {@code russell2000}: fresh Russell 3000
 * additions land at the bottom of the 2000, and deletions exit from the bottom (the 2000). The
 * consequence of an iShares outage is therefore that {@code changes("russell1000")} degrades to
 * empty while {@code changes("russell2000")} still carries every change — a documented, safe skew.
 *
 * <p>Semi-annual by design: outside the reconstitution window (config schedule +
 * {@code window-tail-days} after the effective date) this provider returns an empty list.
 *
 * <p>Only extracted structured fields ({@link IndexChange}: symbol/action/index/dates/source)
 * leave this class — never raw PDF or CSV content (LSEG and BlackRock terms: internal use, no
 * redistribution).
 */
@Component
public class RussellReconstitutionIndexChangeProvider implements IndexChangeProvider {

    private static final Logger log = LoggerFactory.getLogger(RussellReconstitutionIndexChangeProvider.class);

    private static final String SOURCE = "russell_reconstitution";
    private static final String R1000 = "russell1000";
    private static final String R2000 = "russell2000";
    private static final Set<String> SUPPORTED = Set.of(R1000, R2000);
    private static final DateTimeFormatter PDF_DATE = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);
    private static final int MAX_ROWS = 5000;

    private final RestClient lsegHttp;
    private final RestClient isharesHttp;
    private final PdfTextExtractor pdfTextExtractor;
    private final RussellReconstitutionCalendar calendar;
    private final Clock clock;

    private final String pdfPathPattern;
    private final String iwbPath;
    private final String iwmPath;
    private final int windowTailDays;

    // ru3000-additions/deletions PDFs are immutable per date -> long TTL. iShares holdings change
    // daily -> shorter TTL. Both keyed by the resolved URL.
    private final TtlCache<String, List<RussellConstituentListParser.Row>> pdfCache;
    private final TtlCache<String, Set<String>> isharesCache;

    @Autowired
    public RussellReconstitutionIndexChangeProvider(
            PdfTextExtractor pdfTextExtractor,
            @Value("${agora.data.russell.lseg-base-url:https://www.lseg.com}") String lsegBaseUrl,
            @Value("${agora.data.russell.pdf-path-pattern:/content/dam/ftse-russell/en_us/documents/other/{list}-{date}.pdf}")
                    String pdfPathPattern,
            @Value("${agora.data.russell.ishares-base-url:https://www.ishares.com}") String isharesBaseUrl,
            @Value("${agora.data.russell.iwb-path:/us/products/239707/ishares-russell-1000-etf/1521942788811.ajax?fileType=csv&fileName=IWB_holdings&dataType=fund}")
                    String iwbPath,
            @Value("${agora.data.russell.iwm-path:/us/products/239710/ishares-russell-2000-etf/1467271812596.ajax?fileType=csv&fileName=IWM_holdings&dataType=fund}")
                    String iwmPath,
            @Value("${agora.data.russell.user-agent:Mozilla/5.0 (compatible; agora/1.0; research)}") String userAgent,
            @Value("${agora.data.russell.schedule-resource:russell-schedule.yaml}") String scheduleResource,
            @Value("${agora.data.russell.window-tail-days:14}") int windowTailDays,
            @Value("${agora.data.cache.ttl.russell-pdf-seconds:604800}") long pdfTtlSeconds,
            @Value("${agora.data.cache.ttl.russell-ishares-seconds:86400}") long isharesTtlSeconds,
            @Value("${agora.fetch.timeout-ms:15000}") long timeoutMs) {
        this(
                buildHttp(lsegBaseUrl, userAgent, timeoutMs),
                buildHttp(isharesBaseUrl, userAgent, timeoutMs),
                pdfTextExtractor,
                RussellReconstitutionCalendar.fromResource(scheduleResource),
                pdfPathPattern, iwbPath, iwmPath, windowTailDays,
                pdfTtlSeconds, isharesTtlSeconds,
                Clock.systemDefaultZone(), System::currentTimeMillis);
    }

    // Test constructor: pre-built clients + injectable calendar/clock.
    RussellReconstitutionIndexChangeProvider(
            RestClient lsegHttp, RestClient isharesHttp, PdfTextExtractor pdfTextExtractor,
            RussellReconstitutionCalendar calendar, String pdfPathPattern, String iwbPath, String iwmPath,
            int windowTailDays, long pdfTtlSeconds, long isharesTtlSeconds, Clock clock, LongSupplier now) {
        this.lsegHttp = lsegHttp;
        this.isharesHttp = isharesHttp;
        this.pdfTextExtractor = pdfTextExtractor;
        this.calendar = calendar;
        this.pdfPathPattern = pdfPathPattern;
        this.iwbPath = iwbPath;
        this.iwmPath = iwmPath;
        this.windowTailDays = windowTailDays;
        this.clock = clock;
        this.pdfCache = new TtlCache<>(pdfTtlSeconds * 1000L, 64, now);
        this.isharesCache = new TtlCache<>(isharesTtlSeconds * 1000L, 8, now);
    }

    private static RestClient buildHttp(String baseUrl, String userAgent, long timeoutMs) {
        return DataHttp.clientBuilder(timeoutMs)
                .baseUrl(baseUrl)
                .defaultHeader("User-Agent", userAgent)
                .build();
    }

    /** After S&P's provider (order 10); nothing depends on the exact value beyond that ordering. */
    @Override
    public int order() { return 20; }

    /** Changes for russell1000/russell2000 within the active reconstitution window; else empty. Never throws. */
    @Override
    public List<IndexChange> changes(String index) {
        String normalized = (index == null) ? "" : index.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED.contains(normalized)) return List.of();

        LocalDate today = LocalDate.now(clock);
        RussellSchedule window = activeWindow(today);
        if (window == null) return List.of(); // outside the semi-annual window: empty by design

        List<IndexChange> all;
        try {
            all = buildWindowChanges(window, today);
        } catch (RuntimeException e) {
            // buildWindowChanges is already fail-soft per sub-fetch; this only guards a truly
            // unexpected failure so the provider still honours its no-throw contract.
            log.warn("Russell reconstitution build failed for {} ({}): {}", normalized, window.year(), e.toString());
            return List.of();
        }
        List<IndexChange> out = new ArrayList<>();
        for (IndexChange c : all) if (normalized.equals(c.index())) out.add(c);
        return out;
    }

    /** The schedule whose window (preliminary date .. effective date + tail) contains {@code today}. */
    private RussellSchedule activeWindow(LocalDate today) {
        // The June reconstitution can spill into July via the tail; check this year and last year.
        for (int year = today.getYear(); year >= today.getYear() - 1; year--) {
            RussellSchedule s = calendar.forYear(year);
            if (s.preliminaryDate() == null || s.effectiveDate() == null) continue;
            LocalDate end = s.effectiveDate().plusDays(windowTailDays);
            if (!today.isBefore(s.preliminaryDate()) && !today.isAfter(end)) return s;
        }
        return null;
    }

    // Fetches both list PDFs and resolves each ticker's bucket via iShares holdings. Every sub-
    // fetch is fail-soft, so a partial or total network outage degrades gracefully rather than
    // throwing (an outage typically means an empty additions/deletions list, or default buckets).
    private List<IndexChange> buildWindowChanges(RussellSchedule window, LocalDate today) {
        List<RussellConstituentListParser.Row> additions = fetchList("ru3000-additions", window, today);
        List<RussellConstituentListParser.Row> deletions = fetchList("ru3000-deletions", window, today);
        if (additions.isEmpty() && deletions.isEmpty()) return List.of();

        Set<String> iwb = fetchIsharesTickers(iwbPath); // Russell 1000 holdings
        Set<String> iwm = fetchIsharesTickers(iwmPath); // Russell 2000 holdings

        List<IndexChange> out = new ArrayList<>();
        for (RussellConstituentListParser.Row r : additions) {
            out.add(new IndexChange(r.ticker(), "add", resolveBucket(r.ticker(), iwb, iwm),
                    window.preliminaryDate(), window.effectiveDate(), SOURCE));
        }
        for (RussellConstituentListParser.Row r : deletions) {
            out.add(new IndexChange(r.ticker(), "remove", resolveBucket(r.ticker(), iwb, iwm),
                    window.preliminaryDate(), window.effectiveDate(), SOURCE));
        }
        return out;
    }

    /** IWB -> russell1000, IWM -> russell2000, otherwise the default russell2000 (see class doc). */
    private static String resolveBucket(String ticker, Set<String> iwb, Set<String> iwm) {
        String t = ticker.toUpperCase(Locale.ROOT);
        if (iwb.contains(t)) return R1000;
        if (iwm.contains(t)) return R2000;
        return R2000;
    }

    // Selects which dated PDFs to try, first non-empty list wins. Before the effective date the
    // final list does not yet exist, so only the preliminary-dated PDF is attempted — this avoids a
    // guaranteed 404 (and its WARN) on every pre-effective call across the ~5-week window. On/after
    // the effective date the final list is preferred, with the preliminary-dated PDF as a fallback.
    // Never throws.
    private List<RussellConstituentListParser.Row> fetchList(String list, RussellSchedule window, LocalDate today) {
        List<LocalDate> dates = new ArrayList<>();
        boolean effectiveReached = window.effectiveDate() != null && !today.isBefore(window.effectiveDate());
        if (effectiveReached) dates.add(window.effectiveDate());
        dates.add(window.preliminaryDate());
        for (LocalDate date : dates) {
            if (date == null) continue;
            String path = pdfPathPattern
                    .replace("{list}", list)
                    .replace("{date}", PDF_DATE.format(date));
            List<RussellConstituentListParser.Row> rows = fetchAndParsePdf(path);
            if (!rows.isEmpty()) return rows;
        }
        return List.of();
    }

    private List<RussellConstituentListParser.Row> fetchAndParsePdf(String path) {
        try {
            return pdfCache.get(path, () -> {
                byte[] bytes = lsegHttp.get().uri(path).retrieve().body(byte[].class);
                if (bytes == null || bytes.length == 0) throw new IllegalStateException("empty PDF body");
                String text = pdfTextExtractor.extractText(bytes);
                List<RussellConstituentListParser.Row> rows = RussellConstituentListParser.parse(text);
                if (rows.isEmpty()) throw new IllegalStateException("no constituent rows parsed");
                return rows.size() > MAX_ROWS ? rows.subList(0, MAX_ROWS) : rows;
            });
        } catch (RuntimeException e) {
            log.warn("Russell PDF fetch/parse failed, treating list as empty: {} ({})", path, e.getMessage());
            return List.of();
        }
    }

    /**
     * Fetches an iShares holdings CSV and returns the upper-cased ticker set. The endpoint is
     * bot-walled from server IPs and answers with an HTML product page (a lying {@code text/csv}
     * content type); a body that is not CSV, or any fetch failure, degrades to an empty set so the
     * bucket resolution falls back to its default rather than throwing.
     */
    private Set<String> fetchIsharesTickers(String path) {
        try {
            return isharesCache.get(path, () -> {
                String body = isharesHttp.get().uri(path)
                        .header("Accept", "text/csv,application/csv,*/*")
                        .retrieve().body(String.class);
                Set<String> tickers = parseIsharesTickers(body);
                if (tickers.isEmpty()) throw new IllegalStateException("no tickers parsed (walled/HTML?)");
                return tickers;
            });
        } catch (RuntimeException e) {
            log.warn("iShares holdings unavailable, R1000/R2000 bucket resolution will default: {} ({})",
                    path, e.getMessage());
            return Set.of();
        }
    }

    /**
     * Parses the iShares holdings CSV: a metadata preamble, a header row whose first column is
     * {@code Ticker}, then one row per holding, then a disclaimer footer. Returns the upper-cased
     * tickers. An HTML body (bot wall) has no {@code Ticker} header row and yields an empty set.
     * Package-private for tests.
     */
    static Set<String> parseIsharesTickers(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv == null || csv.isBlank() || csv.stripLeading().startsWith("<")) return out;
        String[] lines = csv.split("\\r?\\n");
        int tickerCol = -1;
        for (String line : lines) {
            List<String> fields = splitCsv(line);
            if (fields.isEmpty()) continue;
            if (tickerCol < 0) {
                // Look for the header row: a field equal (case-insensitively) to "Ticker".
                for (int i = 0; i < fields.size(); i++) {
                    if ("Ticker".equalsIgnoreCase(fields.get(i).trim())) { tickerCol = i; break; }
                }
                continue; // the header row itself carries no holding
            }
            if (fields.size() <= tickerCol) break; // footer/disclaimer -> end of the table
            String ticker = fields.get(tickerCol).trim().toUpperCase(Locale.ROOT);
            // Cash/derivative rows use "-" or blank tickers; keep only real equity symbols.
            if (ticker.isEmpty() || "-".equals(ticker)) continue;
            if (ticker.matches("[A-Z][A-Z0-9.]{0,5}")) out.add(ticker);
        }
        return out;
    }

    /** Minimal RFC-4180-ish CSV split: handles double-quoted fields containing commas/quotes. */
    private static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        if (line == null || line.isEmpty()) return out;
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else inQuotes = false;
                } else cur.append(c);
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }
}
