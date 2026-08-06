package de.visterion.agora.fetch.edgar;

import de.visterion.agora.data.DataHttp;
import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.TtlCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.regex.Pattern;

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
    /**
     * Hard guard on total hits fetched across pages, regardless of requested limit.
     *
     * <p>Public because it IS the ceiling the market-wide tools advertise: {@code search_filings}
     * and {@code get_form4_transactions} derive their {@code MAX_LIMIT} from it rather than
     * repeating the literal. Before that, their javadoc claimed a coupling that did not exist —
     * raising either MAX_LIMIT to 2000 would have compiled and then silently capped at 1000.
     */
    public static final int HARD_FETCH_CAP = 1000;
    /**
     * Minimum spacing between the STARTS of two consecutive archive GETs, i.e. 9.09 req/s.
     *
     * <p>SEC publishes a hard ceiling of 10 requests/second — "Current max request rate: 10
     * requests/second" (Accessing EDGAR Data), "our current maximum access rate is 10 requests per
     * second" (webmaster FAQ), and the Internet Security Policy adds that the limit counts
     * "regardless of the number of machines used to submit requests" and that exceeding it gets the
     * IP rate-limited. Re-verified against all three sec.gov pages on 2026-08-06. 110ms leaves ~9%
     * headroom under that ceiling.
     *
     * <p>This is enforced as a rate limit, not as a fixed delay: see the pacing comment in
     * {@link #fetchForm4}. Until BUG-S1a it was slept flat before every fetch, which spaced the
     * GETs THROTTLE_MS + fetch-duration apart (~190ms measured, ~5.3 req/s) and silently spent
     * ~40% of the aggregate deadline on an over-sleep. Do not lower this constant to buy speed —
     * the headroom above is the whole margin against a 403 IP block.
     */
    private static final long THROTTLE_MS = 110;
    /** Aggregate deadline for a single form4Transactions() call's sequential archive GETs. */
    private static final long FORM4_DEADLINE_MS = 30_000;
    /**
     * Backoff before retry attempt 1 and 2 of an EFTS page fetch, i.e. 3 attempts in total.
     *
     * <p>Why a retry exists at all: efts.sec.gov answers 500 intermittently. Measured on the
     * production container 2026-08-06, 2 of 13 EFTS calls in one hour returned
     * {@code status=500}, and the SAME {@code forms=10-12B} query returned both 200 and 500
     * inside that window — so the failure is transient, not query-shaped. Without a retry, one
     * such 500 blinded the spin AND merger hunters for a whole nightly run (both report
     * {@code status=unavailable}, correctly, but the night's data is gone) when a second attempt
     * a moment later would very likely have answered.
     *
     * <p>Why these numbers: 250ms then 750ms, both comfortably above {@link #THROTTLE_MS}=110, so
     * a retry can never push this service faster against SEC than the rate contract allows.
     * Three attempts is where the marginal value collapses — at a per-call failure rate of ~15%
     * (2/13 measured) three independent attempts leave ~0.3% residual, and a fourth would buy
     * 0.05% while doubling the tail latency the caller has to budget for. A geometric step keeps
     * the second wait meaningful against a brief upstream wobble without turning a genuinely dead
     * source into a slow failure.
     *
     * <p>What is retryable: HTTP 5xx and transport-level failures (connect/read timeout,
     * connection reset). Nothing else. A 4xx is NOT retried — SEC answers 403 when it is blocking
     * a client, and hammering a block is how a block becomes a longer block.
     *
     * <p><b>429 is deliberately NOT retried</b>, even though it is the classic "retry me" status.
     * It does not mean "the server hiccuped", it means this client is already over SEC's
     * published ~10 req/s, and SEC escalates sustained excess to a 403 IP ban that costs every
     * hunter for hours. The correct response to a rate refusal is to slow down, and this service
     * already has exactly one mechanism for that ({@link #THROTTLE_MS}); a 250ms in-request
     * backoff is not a rate correction, it is the same excess with a pause in it. So 429 fails
     * fast and loudly, and a recurring one is a signal to raise the throttle, not to retry harder.
     */
    private static final long[] SEARCH_RETRY_BACKOFF_MS = {250, 750};
    /**
     * Aggregate retry budget for ONE search, shared by every page it walks.
     *
     * <p>Per-page retries are right for an isolated 500 but wrong for a systematic outage: a
     * 10-page walk would otherwise cost 10 x 2 retries x backoff plus 20 failing requests. Same
     * shape as {@link #FORM4_DEADLINE_MS} — bound the whole multi-request operation, not each
     * request. Charged with both the backoff slept AND the wall-clock the failed attempt itself
     * burned, so a request that dies on the 15s read timeout exhausts the budget immediately
     * instead of licensing a second 15s wait.
     *
     * <p>Why 2 500 ms — it is the caller's budget that sets this ceiling, not our taste. Dracul
     * reaches this path through Agora's MCP tools:
     * <ul>
     *   <li>{@code search_filings} (the spin + merger hunters) has no per-tool override and runs
     *       on Dracul's global {@code dracul.agora.timeout-ms} = 25 000 ms. A 10-page search
     *       measured 3.7 s on prod 2026-08-04, so ~21 s is unspent.</li>
     *   <li>{@code get_form4_transactions} is budgeted at 45 000 ms against a documented 39 090 ms
     *       worst case, i.e. ~5.9 s of headroom.</li>
     * </ul>
     * 2 500 ms of added retry fits inside the tighter of those two headrooms with room left for
     * the one attempt that may already be in flight when the budget goes negative. A retry that
     * outlives the caller's timeout converts a fast honest failure into a slow one and gains
     * nothing.
     */
    private static final long SEARCH_RETRY_BUDGET_MS = 2_500;
    /**
     * How far past {@code to} the Form-4 search looks for LATE filings — trades inside the
     * caller's window that were filed after it closed (the transaction-date filter below narrows
     * back to the caller's exact window).
     *
     * <p>Only forward. There is deliberately no backward pad: a Form 4's {@code transactionDate}
     * never exceeds its {@code file_date}, so a filing filed before {@code from} cannot carry an
     * in-window transaction. Measured live 2026-08-04 over 100 Form 4s filed 2026-07-10..07-17:
     * 162 of 162 non-derivative transactions had {@code file_date - transactionDate >= 0}, none
     * ahead. The old symmetric pad spent 10 days of a scarce fetch budget on filings that the
     * window filter was guaranteed to discard.
     */
    private static final long FORM4_LATE_FILING_PAD_DAYS = 10;
    /**
     * Default ceiling for a single filing's primary document, overridable via
     * {@code agora.data.edgar.max-filing-bytes}.
     *
     * <p>Chosen from measurement, not from a guess: the 40 most recent DEFM14A primary documents
     * on EFTS (window 2026-02-01..2026-08-01, measured 2026-08-04) run median 3.53 MB, p75
     * 6.05 MB, p90 10.21 MB, max 24.93 MB — and 13 of the 40 exceeded the previous 5 MB cap. A
     * merger proxy IS the document that carries the deal terms, so the old cap rejected exactly
     * the filings the merger hunter exists to read. 32 MiB clears the measured maximum with
     * headroom while still bounding a single request's heap.
     */
    static final long DEFAULT_MAX_FILING_BYTES = 32L * 1024 * 1024;

    /**
     * Default ceiling on filing fetches in flight at once, overridable via
     * {@code agora.data.edgar.max-concurrent-filing-fetches}.
     *
     * <p>The memory ceiling of {@code get_filing_text} must be a property of THIS service, not of
     * how many callers happen to exist. Arithmetic, worst case per in-flight fetch at the 32 MiB
     * cap: the bounded read buffers a {@code byte[]} of up to 32 MiB, decoding it to a String
     * costs another 32 MiB (Latin-1) to 64 MiB (any non-Latin-1 byte), and
     * {@link FilingTextExtractor} builds intermediates of roughly the decoded size again — call it
     * 96-160 MiB. Eight in flight is therefore a ~1.25 GiB peak.
     *
     * <p>Why that number: measured on the production container 2026-08-04, the JVM runs with no
     * {@code -Xmx} and no container memory limit, sizing MaxHeapSize to 15.49 GiB from the LXC
     * HOST's memory while the container itself has 16 GiB and shares it with its neighbours.
     * 1.25 GiB is ~8% of either figure. Unbounded, Tomcat's default 200 request threads could put
     * ~31 GiB in flight — the LXC OOM killer fires at roughly 110 concurrent calls and takes the
     * whole container down, with no OutOfMemoryError in the log to explain it. Raise this only
     * together with a real heap bound (see documentation/capabilities.md).
     */
    public static final int DEFAULT_MAX_CONCURRENT_FILING_FETCHES = 8;

    /** How long a caller waits for a fetch permit before being told the service is busy. Long
     *  enough to ride out a normal multi-MB archive GET, short enough that a request thread is
     *  never parked indefinitely behind a stuck upstream. */
    static final long DEFAULT_FILING_FETCH_QUEUE_TIMEOUT_MS = 30_000;

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
    private final EdgarRequestPacer pacer;
    private final long maxFilingBytes;
    private final TickerUniverse tickerUniverse;
    private final java.util.concurrent.Semaphore filingFetchPermits;
    private final int maxConcurrentFilingFetches;
    private final long filingFetchQueueTimeoutMs;
    private final TtlCache<String, SearchResult> searchCache;
    private final TtlCache<String, Form4Result> form4Cache;
    private final TtlCache<String, FilingText> filingTextCache;

    @Autowired
    public EdgarSearchService(
            @Value("${agora.data.edgar.user-agent}") String userAgent,
            @Value("${agora.data.edgar.efts-base-url:https://efts.sec.gov}") String eftsBase,
            @Value("${agora.data.edgar.archive-base:https://www.sec.gov}") String archiveBase,
            @Value("${agora.data.cache.ttl.filings-seconds:3600}") long ttlSeconds,
            @Value("${agora.fetch.timeout-ms:15000}") long timeoutMs,
            @Value("${agora.data.edgar.max-filing-bytes:33554432}") long maxFilingBytes,
            @Value("${agora.data.edgar.max-concurrent-filing-fetches:8}") int maxConcurrentFilingFetches,
            EdgarCikResolver tickerUniverse) {
        this(buildHttp(eftsBase, EdgarUserAgent.checked(userAgent), timeoutMs),
                buildHttp(archiveBase, userAgent, timeoutMs),
                archiveBase, ttlSeconds, System::currentTimeMillis, REAL_SLEEPER, maxFilingBytes,
                tickerUniverse, maxConcurrentFilingFetches, DEFAULT_FILING_FETCH_QUEUE_TIMEOUT_MS);
    }

    private static RestClient buildHttp(String baseUrl, String userAgent, long timeoutMs) {
        return DataHttp.clientBuilder(timeoutMs)
                .baseUrl(baseUrl)
                .defaultHeader("User-Agent", userAgent)
                .build();
    }

    /** Read timeout of the archive client the test constructor below builds — the default of
     *  {@code agora.fetch.timeout-ms}, i.e. what the production constructor passes. */
    private static final long TEST_ARCHIVE_TIMEOUT_MS = 15_000;

    // Test constructor: pre-built efts RestClient (User-Agent already set) + archive base.
    // Builds a UA-less archive client on archiveBase for the Form-4 XML fetch.
    //
    // Through DataHttp, never a bare RestClient.builder() (BUG-S20): a bare builder sets no
    // request factory, so Spring auto-detects Apache HttpClient 5 from the classpath and its
    // DefaultHttpRequestRetryStrategy silently repeats an idempotent GET on 429/503. Production
    // pins the JDK factory, so a bare client here would make every test that fetches an archive
    // document exercise a retry layer production does not have.
    EdgarSearchService(RestClient http, String archiveBase, long ttlSeconds, LongSupplier now,
                        TickerUniverse tickerUniverse) {
        this(http, DataHttp.clientBuilder(TEST_ARCHIVE_TIMEOUT_MS).baseUrl(archiveBase).build(),
                archiveBase, ttlSeconds, now, tickerUniverse);
    }

    // Full constructor: explicit efts + archive RestClients, real sleeper + default size cap.
    EdgarSearchService(RestClient http, RestClient archiveHttp, String archiveBase, long ttlSeconds, LongSupplier now,
                        TickerUniverse tickerUniverse) {
        this(http, archiveHttp, archiveBase, ttlSeconds, now, REAL_SLEEPER, DEFAULT_MAX_FILING_BYTES,
                tickerUniverse);
    }

    // Test constructor: full control over the throttle sleeper and the filing-body size cap, so
    // throttle/deadline/cap tests run fast and deterministic (no real sleeping, no multi-MB bodies).
    EdgarSearchService(RestClient http, RestClient archiveHttp, String archiveBase, long ttlSeconds, LongSupplier now,
                        Sleeper sleeper, long maxFilingBytes, TickerUniverse tickerUniverse) {
        this(http, archiveHttp, archiveBase, ttlSeconds, now, sleeper, maxFilingBytes, tickerUniverse,
                DEFAULT_MAX_CONCURRENT_FILING_FETCHES, DEFAULT_FILING_FETCH_QUEUE_TIMEOUT_MS);
    }

    // Test constructor: additionally controls the large-fetch concurrency bound and how long a
    // queued caller waits for a permit, so the bound can be exercised without 8 parallel threads
    // and without a 30s wait.
    EdgarSearchService(RestClient http, RestClient archiveHttp, String archiveBase, long ttlSeconds, LongSupplier now,
                        Sleeper sleeper, long maxFilingBytes, TickerUniverse tickerUniverse,
                        int maxConcurrentFilingFetches, long filingFetchQueueTimeoutMs) {
        this.maxConcurrentFilingFetches = Math.max(1, maxConcurrentFilingFetches);
        this.filingFetchQueueTimeoutMs = filingFetchQueueTimeoutMs;
        // Fair, so a queued caller cannot be starved into a spurious "busy" by later arrivals.
        this.filingFetchPermits = new java.util.concurrent.Semaphore(this.maxConcurrentFilingFetches, true);
        this.http = http;
        this.archiveHttp = archiveHttp;
        this.archiveBase = archiveBase;
        this.now = now;
        this.sleeper = sleeper;
        // ONE spacing budget for every sec.gov request this instance makes — the EFTS page walk
        // and the Form-4 archive GETs both draw on it. See EdgarRequestPacer for why the budget
        // must be instance-level rather than local to fetchForm4's loop.
        this.pacer = new EdgarRequestPacer(THROTTLE_MS, now, sleeper);
        this.maxFilingBytes = maxFilingBytes;
        this.tickerUniverse = tickerUniverse;
        this.searchCache = new TtlCache<>(ttlSeconds * 1000L, 512, now);
        this.form4Cache = new TtlCache<>(ttlSeconds * 1000L, 512, now);
        // Filing text bodies run up to ~24KB each — keep this cache small to bound heap.
        this.filingTextCache = new TtlCache<>(ttlSeconds * 1000L, 32, now);
    }

    /**
     * A filing-search answer plus the one truncation signal the row count cannot carry.
     *
     * <p>{@code capped} is true whenever the paging loop stopped for a reason OTHER than the
     * caller's limit or an exhausted window: the {@link #HARD_FETCH_CAP} guard fired, or EFTS
     * answered with an error body instead of a page. It matters because the tools' MAX_LIMIT
     * equals HARD_FETCH_CAP, so "the list is limit-sized" cannot detect the cap once a single
     * hit has been dropped by {@link #parseHit} or by the per-hit catch: 999 of a 50,000-filing
     * window would otherwise be reported as a complete one.
     */
    public record SearchResult(List<FilingHit> hits, boolean capped) {}

    /** Full-text filing search on efts. ticker on a hit may be empty (fresh registrations). */
    public List<FilingHit> search(List<String> forms, String query, LocalDate from, LocalDate to, int limit) {
        return searchResult(forms, query, null, from, to, limit).hits();
    }

    /** @see #search(List, String, LocalDate, LocalDate, int) */
    public List<FilingHit> search(List<String> forms, String query, String cik, LocalDate from, LocalDate to, int limit) {
        return searchResult(forms, query, cik, from, to, limit).hits();
    }

    /** Like {@link #search(List, String, LocalDate, LocalDate, int)} but also reporting whether
     *  the answer was cut by something the row count cannot express — see {@link SearchResult}. */
    public SearchResult searchResult(List<String> forms, String query, LocalDate from, LocalDate to, int limit) {
        return searchResult(forms, query, null, from, to, limit);
    }

    /** Like {@link #searchResult(List, String, LocalDate, LocalDate, int)} but additionally
     *  filtered to filings involving the given entity CIK (efts {@code ciks} filter; zero-padded
     *  10 digits). For ownership forms (3/4/5) the issuer is one of the filing entities, so an
     *  issuer CIK matches its Form-4 filings. {@code cik} null/blank means no entity filter. */
    public SearchResult searchResult(List<String> forms, String query, String cik, LocalDate from, LocalDate to, int limit) {
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

    private SearchResult fetchSearch(String formsCsv, String query, String cik, LocalDate from, LocalDate to, int limit) {
        List<FilingHit> out = new ArrayList<>();
        int offset = 0;
        boolean capped = false;
        // One budget for the whole page walk — see SEARCH_RETRY_BUDGET_MS.
        RetryBudget retryBudget = new RetryBudget(SEARCH_RETRY_BUDGET_MS);
        while (out.size() < limit) {
            if (offset >= HARD_FETCH_CAP) { capped = true; break; }
            JsonNode search = fetchPage(formsCsv, query, cik, from, to, offset, retryBudget);
            JsonNode hitsNode = search == null ? null : search.path("hits");
            JsonNode hits = hitsNode == null ? null : hitsNode.path("hits");
            if (hits == null || !hits.isArray() || hits.isEmpty()) {
                // EFTS reports a refused window with HTTP 200 and an OpenSearch error body — no
                // `hits` key at all (verified live 2026-08-04 at from=10000: "Result window is
                // too large ..."). That is NOT an exhausted window, so it must not be reported
                // as a complete one.
                if (search != null && search.has("errorType")) {
                    capped = true;
                    log.debug("EFTS search returned an error body instead of a page (forms={}, query={}, from={}): {}",
                            formsCsv, query, offset, search.path("errorMessage").asString(""));
                }
                break;
            }
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
        return new SearchResult(List.copyOf(out), capped);
    }

    /** Mutable remaining-retry-time counter, shared by every page of one search. */
    private static final class RetryBudget {
        private long remainingMs;
        RetryBudget(long remainingMs) { this.remainingMs = remainingMs; }
    }

    /**
     * Fetches one EFTS page, retrying a transient failure per {@link #SEARCH_RETRY_BACKOFF_MS}
     * within the search's shared {@link RetryBudget}.
     *
     * <p>When the retries are exhausted — by attempt count, by budget, or because the failure was
     * never retryable — this throws EXACTLY what it threw before the retry existed: the caller's
     * {@code status=unavailable} reporting downstream is correct and must not change shape.
     */
    private JsonNode fetchPage(String formsCsv, String query, String cik, LocalDate from, LocalDate to,
                               int offset, RetryBudget budget) {
        for (int attempt = 1; ; attempt++) {
            long startedAt = now.getAsLong();
            try {
                return requestPage(formsCsv, query, cik, from, to, offset);
            } catch (MarketDataException e) {
                throw e;
            } catch (Exception e) {
                long spentMs = Math.max(0, now.getAsLong() - startedAt);
                if (attempt > SEARCH_RETRY_BACKOFF_MS.length || !isRetryable(e)) throw unreachable(e);
                long backoffMs = SEARCH_RETRY_BACKOFF_MS[attempt - 1];
                if (budget.remainingMs - spentMs < backoffMs) {
                    log.warn("EFTS page fetch failed ({}) and the {}ms aggregate retry budget for this "
                                    + "search is exhausted — giving up (forms={}, query={}, offset={})",
                            describe(e), SEARCH_RETRY_BUDGET_MS, formsCsv, query, offset);
                    throw unreachable(e);
                }
                budget.remainingMs -= spentMs + backoffMs;
                // Loud on purpose: a retry that silently succeeds hides a degrading upstream, and
                // the daily analysis can only flag a flaky source it can see.
                log.warn("EFTS page fetch failed ({}) on attempt {}/{} — retrying in {}ms "
                                + "(forms={}, query={}, offset={})",
                        describe(e), attempt, SEARCH_RETRY_BACKOFF_MS.length + 1, backoffMs,
                        formsCsv, query, offset);
                try {
                    sleeper.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw unreachable(e);
                }
            }
        }
    }

    /**
     * Retry only what a second attempt can plausibly fix: a 5xx (the measured EFTS failure mode)
     * and a transport-level failure — connect/read timeout, connection reset — which surfaces as
     * {@link org.springframework.web.client.ResourceAccessException}. Every 4xx, 429 included, is
     * a refusal by SEC rather than a hiccup; see {@link #SEARCH_RETRY_BACKOFF_MS} for why
     * retrying one makes the situation worse. Anything else (a JSON parse failure, say) is
     * deterministic and would fail identically on attempt two.
     */
    private static boolean isRetryable(Exception e) {
        if (e instanceof org.springframework.web.client.RestClientResponseException r) {
            if (r.getStatusCode().value() == 429) return false;
            return r.getStatusCode().is5xxServerError();
        }
        return e instanceof org.springframework.web.client.ResourceAccessException;
    }

    /** Log-safe one-liner: the HTTP status when there was one, else the transport failure class. */
    private static String describe(Exception e) {
        return e instanceof org.springframework.web.client.RestClientResponseException r
                ? "status=" + r.getStatusCode().value()
                : "transport=" + e.getClass().getSimpleName();
    }

    private static MarketDataException unreachable(Exception e) {
        return new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                "EDGAR search unreachable: " + e.getMessage(), e);
    }

    /** One EFTS request, no retry, no exception translation — the unit {@link #fetchPage} retries.
     *
     *  <p>Paced on the SAME budget as the archive GETs (see {@link EdgarRequestPacer}): efts.sec.gov
     *  and www.sec.gov are one host family for the purposes of SEC's per-user rate ceiling, and a
     *  page walk is up to {@link #HARD_FETCH_CAP}/{@link #PAGE_SIZE} requests in a row. Note the
     *  pacing sits INSIDE the unit that {@link #fetchPage} retries, so a retried attempt is spaced
     *  from its predecessor too — the retry backoff (250/750ms) already exceeds the spacing, so in
     *  practice this only ever costs a retry zero extra wait. */
    private JsonNode requestPage(String formsCsv, String query, String cik, LocalDate from, LocalDate to, int offset) {
        pacer.acquireUninterruptibly();
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

        // The efts `_source` has NO `tickers` key (verified against live EFTS). The ticker exists
        // only inside display_names[0], as the parenthesised group in front of the "(CIK ...)"
        // group: "Arcosa, Inc.  (ACA)  (CIK 0001739445)". `company` above already dropped the CIK
        // group, so the ticker group — when present — is now its trailing "(...)". That group is
        // a CANDIDATE only; it is validated against the filer's own CIK below.
        String ticker = extractTicker(company, firstCik(src));

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
     * A US exchange ticker: 1-5 alphanumerics, optionally followed by a '.'/'-' separated share
     * class suffix of 1-2 characters (BRK.B, RDS-A). A cheap shape pre-filter only — the symbol
     * is decided by the CIK check in {@link #extractTicker}, never by this pattern.
     */
    private static final Pattern TICKER = Pattern.compile("[A-Za-z0-9]{1,5}([.-][A-Za-z0-9]{1,2})?");

    /** First entry of {@code _source.ciks}, i.e. the CIK display_names[0] belongs to. */
    private static String firstCik(JsonNode src) {
        JsonNode ciks = src.path("ciks");
        return ciks.isArray() && !ciks.isEmpty() ? ciks.get(0).asString("") : "";
    }

    /**
     * Extracts the ticker from an efts display name whose " (CIK ...)" group has already been
     * stripped, i.e. from {@code "Arcosa, Inc.  (ACA)"} -> {@code "ACA"}, and from a multi-symbol
     * filer's {@code "WPP plc  (WPP, WPPGF)"} -> {@code "WPP"}.
     *
     * <p><b>The trailing group is a candidate, not an answer.</b> Anchoring on the end of the name
     * is NOT sufficient, because EDGAR conformed names themselves end in parentheticals: measured
     * over SEC's full {@code cik-lookup-data.txt} (1,053,510 names, 2026-08-04), 635 names end in
     * a group this pattern accepts and 183 of those collide with a real listed ticker. Live EFTS
     * examples: {@code "Grayscale Story Trust (IP)"}, {@code "ACUITY INC. (DE)"},
     * {@code "MUZINICH & CO. LTD (UK)"}, {@code "Tower Research Capital LLC (TRC)"}. Emitting
     * those is strictly worse than emitting nothing — an invented symbol routes a quote lookup and
     * a merger spread to a different company AND slips past the empty-hit guard below.
     *
     * <p>So a candidate is only returned when the FILER'S OWN CIK is listed under it in SEC's
     * {@code company_tickers.json} ({@link TickerUniverse}). EFTS prints all of a filer's symbols
     * in one comma-separated group ({@code "(EQH, EQH-PA, EQH-PC)"}) — 14.5% of a 3,454-hit live
     * sample, and 25% of DEFM14A merger targets — so the group is split and the FIRST element the
     * CIK is listed under wins; SEC orders that file by market cap descending and EFTS prints the
     * primary symbol first, so this is the primary listing, not an arbitrary class.
     *
     * <p>The returned spelling is the one EFTS printed (upper-cased): SEC writes share classes
     * with '-' and callers commonly with '.', so both spellings are accepted for the match while
     * the caller keeps seeing the symbol as it appeared in the filing index.
     *
     * <p>An unknown CIK, a hit with no {@code ciks} at all, and an UNAVAILABLE ticker universe all
     * yield {@code ""}. That last case is deliberate: a lookup failure must not silently degrade
     * back into guessing — an absent symbol is recoverable, a wrong one is not.
     */
    private String extractTicker(String companyWithTicker, String cik) {
        String s = companyWithTicker.strip();
        if (!s.endsWith(")")) return "";
        int open = s.lastIndexOf('(');
        if (open < 0) return "";
        List<String> listed = tickerUniverse.tickersForCik(cik);
        if (listed.isEmpty()) return "";
        for (String part : s.substring(open + 1, s.length() - 1).split(",")) {
            String candidate = part.strip().toUpperCase();
            if (candidate.isEmpty() || !TICKER.matcher(candidate).matches()) continue;
            if (listed.contains(candidate)
                    || listed.contains(EdgarCikResolver.swapShareClassSeparator(candidate))) {
                return candidate;
            }
        }
        return "";
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
     * in [from,to] — filed in the window OR filed late. Two efts searches for {@code forms=4}
     * (see {@link #FORM4_ROOT_FORM}: the root form already includes its amendments) over disjoint
     * filing-date ranges — the caller's window first, then the following
     * {@value #FORM4_LATE_FILING_PAD_DAYS} days for late filings — then a per-hit Form-4 XML
     * fetch + DOM parse, throttled to stay under SEC's request-rate limit with an aggregate
     * deadline. Malformed hits/XML are skipped (never throw per-hit); an efts search failure
     * surfaces as {@link MarketDataException}.
     *
     * <p>Ordering: EFTS has no documented usable sort parameter (a simple {@code sort=} value is
     * rejected), but its default order is deterministic — {@code file_date} descending with the
     * hit {@code _id} as tiebreak (verified 2026-07). A limit/deadline cut therefore drops the
     * OLDEST filings of the range, never a random subset — which is precisely why the caller's
     * window must be searched before the late-filing pad rather than as one padded range.
     *
     * <p>Budget: a market-wide 7-day window holds ~1,700 Form-4 filings (measured live 2026-08-04,
     * see {@link #FORM4_ROOT_FORM}), so {@link #HARD_FETCH_CAP} and then the
     * {@value #FORM4_DEADLINE_MS}ms deadline both bind long before the window is exhausted. Such a
     * result is reported {@code truncated=true} and is a SAMPLE of the newest filings in the
     * window, not the window.
     *
     * <p>How much of it is read: since BUG-S1a the per-filing cost is {@link #THROTTLE_MS} itself
     * rather than THROTTLE_MS + the fetch, because the throttle spaces the request STARTS instead
     * of delaying each one. So the deadline buys 30 000 / 110 = <b>~272 filings per call</b>, up
     * from the ~159 the flat delay bought at the measured ~190ms per filing — roughly 16% of a
     * market-wide week instead of 9%.
     *
     * <p><b>These two numbers are DERIVED, not measured.</b> They come from the deterministic
     * fake-clock arithmetic in {@code EdgarSearchServiceTest} (an 80ms fetch yields exactly 273
     * filings under the new pacing and 159 under the old), not from a production run. A live run
     * will differ with the real archive latency: any fetch slower than 110ms paces itself and
     * pushes the count below 272. Replace this paragraph with a measured figure once a prod run
     * has reported one.
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

    /**
     * The forms argument of every Form-4 search: the ROOT form only.
     *
     * <p>Never {@code ["4", "4/A"]}. EFTS's {@code forms} parameter selects ROOT forms and always
     * includes their amendments, while an explicit {@code "X/A"} token intersects the whole query
     * down to that amendment type. Measured live 2026-08-04 on window 2026-07-20..2026-07-27:
     * {@code forms=4} -> 1,697 hits (a single 100-hit page carried 99 file_type "4" and 1 "4/A",
     * and the response's {@code aggregations.form_filter} bucket for that amendment was keyed
     * "4"); {@code forms=4/A} -> 38; {@code forms=4,4/A} -> 38; {@code forms=4/A,4} -> 38;
     * repeated {@code forms=4&forms=4/A} -> 38. That the token is a global narrowing rather than
     * a bad union is settled by {@code forms=3,4/A} -> 0 and {@code forms=3,4,4/A} -> 38.
     *
     * <p>CSV across ROOT forms is a correct union and needs no workaround:
     * {@code forms=3} -> 312, {@code forms=4} -> 1,697, {@code forms=3,4} -> 2,009 exactly.
     *
     * <p>So there is no encoding that unions 4 and 4/A, and none is needed — {@code forms=4}
     * already IS that union. The old {@code "4,4/A"} showed the market-wide hunter 1.6% of its
     * window, amendments only.
     */
    private static final List<String> FORM4_ROOT_FORM = List.of("4");

    /**
     * A caller window this wide or narrower (in calendar days, inclusive) has its late-filing pad
     * walked OLDEST-first; a wider one keeps the single descending pad search.
     *
     * <p><b>Why the order cannot be fixed.</b> SEC gives a filer two business days: "Form 4 must be
     * filed before the end of the second business day following the day on which the subject
     * transaction has been executed" (17 CFR 240.16a-3(g)(1), read from eCFR 2026-08-06; the same
     * rule's (g)(2)-(g)(4) can defer the deemed execution date by up to three further business
     * days, which is what the {@value #FORM4_LATE_FILING_PAD_DAYS}-day pad width covers). So a
     * transaction dated D is reported by filings filed D, D+1 or D+2 — mostly NOT on D.
     *
     * <p>For a WIDE window, that lag is a rounding error: nearly every day in [from,to] has its
     * filings inside [from,to] too, so the window search holds the bulk of the answer and must be
     * read first. That is measured — see the replay in {@link #fetchForm4}.
     *
     * <p>For a NARROW window it inverts. At from=to=D the window search returns only the filings
     * filed ON D — the same-day-filed minority — and at ~243 filings for a market-wide day it eats
     * almost the whole {@link #MAX_FILINGS_PER_DEADLINE} budget. The sliver left over then goes to
     * the pad, and because EFTS sorts file_date DESCENDING that sliver is spent on filings filed
     * D+{@value #FORM4_LATE_FILING_PAD_DAYS}, whose transactions post-date the window and are ALL
     * discarded by the transaction-date filter in {@link #parseForm4}. The filings that actually
     * carry D's trades — those filed D+1 and D+2 — sit at the far end of that descending list and
     * are never reached. A day slice could therefore only ever return the same-day-filed subset of
     * its day, and no number of slices recovered the rest.
     *
     * <p><b>Why this was not caught earlier:</b> the live replay that established window-first
     * ordering used an EIGHT-day window, where filings filed in-window mostly report in-window
     * trades. That result does not generalise to a one-day window, and the one-day configuration
     * was never measured before it shipped. A width-dependent rule is the smallest change that
     * keeps the measured wide-window behaviour and fixes the narrow one.
     *
     * <p><b>Why 4.</b> The threshold is the widest calendar span the two-business-day deadline can
     * occupy: a Thursday or Friday trade reports on the following Monday or Tuesday, i.e. D+4
     * calendar days (a holiday can stretch it further, which only makes a window at the threshold
     * more pad-dominated, never less). A window at or below that width has essentially no day whose
     * filings are guaranteed to fall inside it. It also sits comfortably below the 8-day replay
     * window, so that measured case keeps its measured behaviour.
     */
    private static final long FORM4_NARROW_WINDOW_MAX_DAYS = 4;

    /**
     * How many filings one call's aggregate deadline can fetch, at one request per
     * {@link #THROTTLE_MS}: {@value #FORM4_DEADLINE_MS}/110 = 272.
     *
     * <p>DERIVED, not measured — it assumes every archive GET returns inside the spacing window.
     * Used only to stop the narrow-window pad walk once collecting more hits could not lead to
     * more fetches, so an over-estimate costs at most one extra EFTS request and never drops a
     * filing.
     */
    private static final long MAX_FILINGS_PER_DEADLINE = FORM4_DEADLINE_MS / THROTTLE_MS;

    /** True when [from,to] spans at most {@value #FORM4_NARROW_WINDOW_MAX_DAYS} calendar days, i.e.
     *  when the late-filing pad rather than the window itself holds most of the answer. An open
     *  {@code from} is unbounded and therefore never narrow. */
    private static boolean isNarrowWindow(LocalDate from, LocalDate to) {
        if (from == null || to == null) return false;
        return java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1 <= FORM4_NARROW_WINDOW_MAX_DAYS;
    }

    private Form4Result fetchForm4(String cik, LocalDate from, LocalDate to, int limit) {
        // Two searches over DISJOINT filing-date ranges, in strict priority order.
        //
        // Why not one padded range: EFTS returns file_date DESCENDING, and the fetch budget
        // (HARD_FETCH_CAP hits, then a 30s aggregate deadline over ~110ms-spaced archive GETs) is
        // an order of magnitude smaller than a market-wide window — so whichever range sorts
        // FIRST is the only one that is ever read. Searching [from-pad, to+pad] therefore spends
        // the entire budget inside the pad, on filings whose transactions post-date the caller's
        // window and are then all discarded by the transaction-date filter.
        //
        // Measured live 2026-08-04, caller window 2026-07-20..2026-07-27, full end-to-end replay
        // against real EFTS + real archive GETs: padded-range-first read 143 filings, all filed
        // 2026-08-03, and yielded 0 in-window transactions. Caller-window-first read 139 filings
        // and yielded 187 in-window transactions (51 open-market buys, 6 above 500k USD).
        List<FilingHit> hits = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        boolean capped = false;

        // 1) the caller's exact window — where the in-window transactions actually are.
        SearchResult window = searchResult(FORM4_ROOT_FORM, null, cik, from, to, limit);
        capped |= window.capped();
        addNew(hits, seen, window.hits(), limit);

        // 2) only then the late-filing pad: trades inside the window, filed after it closed. Its
        // share of the limit is what the window search left over, so the two searches together
        // still honour exactly one `limit` and one HARD_FETCH_CAP-bounded pass each.
        //
        // The pad is walked in one of two ORDERS, decided by the window's width — see
        // FORM4_NARROW_WINDOW_MAX_DAYS for why a fixed order cannot be right for both.
        if (to != null && hits.size() < limit) {
            if (isNarrowWindow(from, to)) {
                // Narrow window: walk the pad OLDEST-first, one day per search. EFTS sorts
                // file_date DESCENDING and offers no usable sort parameter, so the only way to
                // reach the near end of the pad first is to ask for it as its own day. The walk
                // stops as soon as enough hits are in hand to saturate what the deadline can
                // actually fetch, so the common case costs exactly ONE extra EFTS request.
                long padTarget = Math.min(limit, MAX_FILINGS_PER_DEADLINE);
                for (long d = 1; d <= FORM4_LATE_FILING_PAD_DAYS && hits.size() < limit; d++) {
                    LocalDate day = to.plusDays(d);
                    SearchResult late = searchResult(FORM4_ROOT_FORM, null, cik, day, day,
                            limit - hits.size());
                    capped |= late.capped();
                    addNew(hits, seen, late.hits(), limit);
                    if (hits.size() >= padTarget) break;
                }
            } else {
                SearchResult late = searchResult(FORM4_ROOT_FORM, null, cik,
                        to.plusDays(1), to.plusDays(FORM4_LATE_FILING_PAD_DAYS), limit - hits.size());
                capped |= late.capped();
                addNew(hits, seen, late.hits(), limit);
            }
        }

        List<Form4Transaction> out = new ArrayList<>();
        // A full limit-sized hit list means a search itself was cut (more filings may exist).
        // The row count alone is not enough: with limit == HARD_FETCH_CAP a single hit dropped by
        // parseHit leaves 999 rows on a 50,000-filing window, so each search's own cap flag is
        // OR-ed in rather than relying on the 30s aggregate deadline to notice. A pad skipped
        // because the window search already filled the limit is itself a cut — late filings were
        // never looked at — and needs no extra term: that is exactly the case in which
        // `hits.size() >= limit` holds.
        boolean truncated = capped || hits.size() >= limit;
        long deadline = now.getAsLong() + FORM4_DEADLINE_MS;
        // The archive GETs are spaced by the instance-wide pacer, applied inside parseForm4 right
        // before each request (see EdgarRequestPacer, and requestPage for the EFTS half of the same
        // budget). This loop therefore only owns the DEADLINE; it must not do its own spacing, or
        // the process would have two independent limiters that each believe they own the headroom.
        for (FilingHit hit : hits) {
            if (out.size() >= limit) {
                truncated = true;   // hits remain unprocessed beyond the transaction limit
                break;
            }
            if (now.getAsLong() >= deadline) {
                truncated = true;
                break;
            }
            if (Thread.currentThread().isInterrupted()) {
                truncated = true;   // the pacer was interrupted mid-wait; stop rather than sprint
                break;
            }
            try {
                parseForm4(hit, out, from, to);
            } catch (Exception e) {
                // skip malformed individual filings; continue
            }
        }
        return new Form4Result(out, truncated);
    }

    /**
     * Appends hits not already collected, up to {@code limit}. The two Form-4 searches cover
     * disjoint filing-date ranges so an overlap should not occur — but an accession fetched twice
     * would double-count every transaction in it, which is worse than the cost of a hash lookup.
     * Hits with no accession cannot be identified and are always kept (they carry no url, so
     * {@link #parseForm4} returns without a fetch anyway).
     */
    private static void addNew(List<FilingHit> into, java.util.Set<String> seen,
                               List<FilingHit> more, int limit) {
        for (FilingHit h : more) {
            if (into.size() >= limit) return;
            String acc = h.accession();
            if (acc != null && !acc.isEmpty() && !seen.add(acc)) continue;
            into.add(h);
        }
    }

    /** A filing's extracted summary/term-sheet text plus extraction metadata. */
    public record FilingText(String text, boolean sectionFound, boolean truncated, int charCount, String sourceUrl) {}

    /**
     * Fetch a filing's primary document from the SEC archive and extract its summary/term-sheet
     * text. {@code url} MUST be an archive URL under the configured archive base (SSRF guard).
     * Throws {@link MarketDataException} on a non-archive url, a fetch failure, an oversized
     * body, or an empty document.
     *
     * <p>An oversized body is reported as {@link MarketDataException.Kind#TOO_LARGE} with a
     * message starting {@code filing_too_large:} — distinct from the {@code UNAVAILABLE} kind
     * used for a genuinely unreachable source. The bound is
     * {@code agora.data.edgar.max-filing-bytes} (default {@value #DEFAULT_MAX_FILING_BYTES}).
     *
     * <p>Deliberately NOT truncated: {@link FilingTextExtractor} finds the summary section by
     * taking the LAST occurrence of its heading in the document (to skip the table of contents),
     * so a byte-truncated document would silently yield the TOC entry instead of the real
     * section — a wrong answer presented as a complete one. Rejecting loudly is correct here;
     * the fix for an over-cap filing is to raise the property, not to return half a document.
     */
    public FilingText filingText(String url) {
        if (url == null || !url.startsWith(archiveBase + "/Archives/")) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "not an SEC archive url: " + url, null);
        }
        return filingTextCache.get(cacheKey("text", url), () -> fetchFilingTextBounded(url));
    }

    /**
     * Bounds how many filing bodies are in memory at once (see
     * {@link #DEFAULT_MAX_CONCURRENT_FILING_FETCHES} for the arithmetic behind the number).
     *
     * <p>The permit covers the whole read-decode-extract span, i.e. exactly the window in which a
     * fetch holds its multi-MB buffers, and is released in a {@code finally} so a failing fetch
     * cannot leak it. Cache hits never take a permit — {@link TtlCache#get} only invokes the
     * loader on a miss — so a warm document costs nothing.
     *
     * <p>Over the bound the call is REFUSED after a bounded wait rather than queued forever: a
     * parked request thread is itself a resource, and a caller that is told "busy" can retry,
     * while one that is silently OOM-killed cannot.
     */
    private FilingText fetchFilingTextBounded(String url) {
        boolean acquired;
        try {
            acquired = filingFetchPermits.tryAcquire(filingFetchQueueTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "filing fetch interrupted while queued: " + url, ie);
        }
        if (!acquired) {
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "filing_fetch_busy: " + maxConcurrentFilingFetches + " filing fetches already in flight, "
                            + "waited " + filingFetchQueueTimeoutMs + "ms for a slot "
                            + "(raise agora.data.edgar.max-concurrent-filing-fetches, but only together "
                            + "with a real heap bound): " + url, null);
        }
        try {
            return fetchFilingText(url);
        } finally {
            filingFetchPermits.release();
        }
    }

    private FilingText fetchFilingText(String url) {
        String raw;
        try {
            raw = archiveHttp.get().uri(url).exchange((request, response) -> {
                long contentLength = response.getHeaders().getContentLength();
                if (contentLength > maxFilingBytes) {
                    throw tooLarge(contentLength + " bytes (Content-Length)", url);
                }
                try (InputStream body = response.getBody()) {
                    // Bounded read regardless of (possibly absent/lying) Content-Length: never
                    // buffer more than maxFilingBytes+1 bytes, so we can detect an over-cap body.
                    byte[] buf = body.readNBytes((int) Math.min(maxFilingBytes + 1, Integer.MAX_VALUE));
                    if (buf.length > maxFilingBytes) {
                        // Same cap, same token as the pre-check — only the evidence differs. This
                        // branch is the one that fires when the response has no Content-Length.
                        throw tooLarge("more than " + maxFilingBytes + " bytes (no usable Content-Length)", url);
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

    /**
     * The single place the "this document is too big" failure is built. Both the Content-Length
     * pre-check and the post-read bounded-read check route through it so the two are guaranteed
     * to carry the same kind, the same leading token and the same cap value.
     *
     * <p>The message deliberately opens with the stable machine token {@code filing_too_large:}
     * and the kind is {@link MarketDataException.Kind#TOO_LARGE}, never {@code UNAVAILABLE}:
     * consumers must be able to tell a permanently-oversized document from a dead source. Before
     * this, Dracul logged both as "Agora unreachable for get_filing_text".
     */
    private MarketDataException tooLarge(String measured, String url) {
        return new MarketDataException(MarketDataException.Kind.TOO_LARGE,
                "filing_too_large: document is " + measured + ", cap is " + maxFilingBytes
                        + " bytes (raise agora.data.edgar.max-filing-bytes): " + url, null);
    }

    private void parseForm4(FilingHit hit, List<Form4Transaction> out, LocalDate from, LocalDate to) throws Exception {
        if (hit.url() == null || hit.url().isEmpty()) return;

        String xml;
        try {
            // Paced on the shared budget, immediately before the request rather than in the caller's
            // loop: a hit with no url returns above without spending a slot it never used.
            pacer.acquireUninterruptibly();
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
