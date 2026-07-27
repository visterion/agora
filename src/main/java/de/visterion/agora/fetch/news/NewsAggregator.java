package de.visterion.agora.fetch.news;

import de.visterion.agora.data.MarketDataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * Fans out one company-news request to all configured {@link NewsProvider}s in parallel,
 * then merges: URL-dedup before title-dedup (first hit wins in provider order), sort by
 * datetime descending (nulls last), optional sourceTypes filter (before the cap), cap at
 * maxItems. Failed or over-budget providers degrade to per-provider warnings (partial
 * results); only a total failure (or no configured provider) throws.
 * Domain derivation (lowercase host, www.-stripped, null on unparsable) is
 * centralized here after merge — providers never set it.
 */
public class NewsAggregator {

    /**
     * Total wall-clock budget for the parallel provider fan-out, in milliseconds.
     * Must stay below consumer MCP client timeouts: a slow provider has to degrade into
     * a partial result with a warning, never into a total timeout at the consumer.
     * Pinned by NewsAggregatorTest#budgetConstantStaysBelowConsumerMcpTimeouts.
     */
    public static final long TOTAL_BUDGET_MS = 7000;

    /**
     * NewsAggregator is invoked once per symbol and has no concept of a "run", so "one WARN
     * per run" isn't implementable here. Instead: at most one summary WARN per provider per
     * this many ms of rate-limited (cooldown) skips, so one real 429 upstream never turns into
     * one WARN per subsequent symbol. Pinned by NewsAggregatorCooldownLoggingTest.
     */
    static final long RATE_LIMIT_WARN_WINDOW_MS = 5 * 60 * 1000L;

    /**
     * Second, size-based flush trigger, alongside the time window above. Agora's workload is
     * bounded batch runs (a Strigoi hunt over N symbols), not a continuous stream: the
     * motivating incident was a genuine 429 at symbol #5 of a 145-symbol run, with the
     * remaining ~140 skipped inside a single run that then ends — entirely inside one
     * {@link #RATE_LIMIT_WARN_WINDOW_MS} window, with no further call afterwards to ever
     * trigger the time-based flush. Relying on the window alone would report zero skips for
     * exactly the incident this task exists to fix. Once the accumulated skip count for a
     * provider reaches this threshold, it flushes immediately instead of waiting for the
     * window to elapse, then resets its own counter (independent of the window's start time,
     * which is untouched) — so a large burst self-reports promptly in roughly-threshold-sized
     * chunks, while an isolated skip or two (nowhere near this threshold) still stays quiet.
     * 20 is comfortably above "isolated retry noise" and comfortably below the ~140-skip scale
     * of the motivating incident, so that incident would have produced several summary WARNs
     * instead of zero. Pinned by NewsAggregatorCooldownLoggingTest.
     */
    static final int RATE_LIMIT_WARN_BURST_THRESHOLD = 20;

    private static final Logger log = LoggerFactory.getLogger(NewsAggregator.class);
    private static final Set<String> KNOWN_SOURCE_TYPES = Set.of("news", "social");

    /** Merged, deduped, sorted, capped items plus one sanitized warning per degraded provider. */
    public record AggregatedNews(List<NewsItem> items, List<String> warnings) {}

    private final List<NewsProvider> providers;
    private final int maxItems;
    private final long budgetMs;
    private final LongSupplier clock;

    // Per-provider rate-limit-skip summary window state. NewsAggregator is a Spring singleton
    // (one instance for the process lifetime, called once per symbol), so this state
    // accumulates across calls by design.
    private final Map<String, Long> rateLimitWindowStartMs = new ConcurrentHashMap<>();
    private final Map<String, Integer> rateLimitSkipCount = new ConcurrentHashMap<>();

    public NewsAggregator(List<NewsProvider> providers, int maxItems) {
        this(providers, maxItems, TOTAL_BUDGET_MS, System::currentTimeMillis);
    }

    NewsAggregator(List<NewsProvider> providers, int maxItems, long budgetMs) {
        this(providers, maxItems, budgetMs, System::currentTimeMillis);
    }

    NewsAggregator(List<NewsProvider> providers, int maxItems, long budgetMs, LongSupplier clock) {
        this.providers = List.copyOf(providers);
        this.maxItems = maxItems;
        this.budgetMs = budgetMs;
        this.clock = clock;
    }

    /** Provider chain in dedup-priority order (for wiring tests). */
    List<NewsProvider> providers() { return providers; }

    public AggregatedNews aggregate(String symbol, LocalDate from, LocalDate to, Set<String> sourceTypes) {
        List<NewsProvider> active = providers.stream().filter(NewsProvider::configured).toList();
        if (active.isEmpty())
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "no news providers configured", null);

        List<String> warnings = new ArrayList<>();
        Set<String> wanted = normalizeSourceTypes(sourceTypes, warnings);
        List<List<NewsItem>> results = collect(active, symbol, from, to, warnings);

        boolean anySuccess = results.stream().anyMatch(r -> r != null);
        if (!anySuccess)
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "all news providers failed: " + String.join("; ", warnings), null);

        List<NewsItem> merged = merge(results);
        merged.replaceAll(NewsAggregator::withDomain);
        if (wanted != null)
            merged.removeIf(n -> n.sourceType() == null
                    || !wanted.contains(n.sourceType().toLowerCase(Locale.ROOT)));
        merged.sort(Comparator.comparing(NewsItem::datetime,
                Comparator.nullsLast(Comparator.reverseOrder())));
        if (merged.size() > maxItems)
            merged = new ArrayList<>(merged.subList(0, maxItems));
        return new AggregatedNews(List.copyOf(merged), List.copyOf(warnings));
    }

    /**
     * Parallel fan-out on virtual threads with a hard total budget. On deadline the
     * outstanding futures are cancelled ({@code cancel(true)}) and the pool is shut down
     * ({@code shutdownNow()}) BEFORE the implicit {@code close()}, which would otherwise
     * await running tasks — a hanging provider HTTP call must never stall the aggregate.
     * The JDK HttpClient is interrupt-responsive, so cancellation covers all providers.
     */
    private List<List<NewsItem>> collect(List<NewsProvider> active, String symbol,
                                         LocalDate from, LocalDate to, List<String> warnings) {
        List<List<NewsItem>> results = new ArrayList<>();
        for (int i = 0; i < active.size(); i++) results.add(null);
        long deadlineNanos = System.nanoTime() + budgetMs * 1_000_000L;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<NewsItem>>> futures = new ArrayList<>();
            for (NewsProvider p : active)
                futures.add(pool.submit(() -> p.companyNews(symbol, from, to)));
            for (int i = 0; i < active.size(); i++) {
                NewsProvider p = active.get(i);
                long remaining = deadlineNanos - System.nanoTime();
                try {
                    results.set(i, futures.get(i).get(Math.max(remaining, 0L), TimeUnit.NANOSECONDS));
                } catch (TimeoutException e) {
                    futures.get(i).cancel(true);
                    warnings.add(p.id() + ": timeout");
                    log.warn("news provider {} dropped: over total budget ({} ms)", p.id(), budgetMs);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    warnings.add(warningFor(p, cause));
                    if (isRateLimitCooldown(cause)) {
                        // Silent-by-design rejection (RssNewsProvider's shared host cooldown
                        // after an earlier real 429) — never worth a WARN per symbol. Still
                        // client-visible via the warning above; only the log level moves.
                        log.debug("news provider {} skipped for {}: rate limited (cooldown)", p.id(), symbol);
                        recordRateLimitSkip(p.id());
                    } else {
                        log.warn("news provider {} failed for {}", p.id(), symbol, cause);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                            "news aggregation interrupted", e);
                }
            }
            pool.shutdownNow(); // interrupt stragglers BEFORE implicit close() awaits them
        }
        return results;
    }

    private static boolean isRateLimitCooldown(Throwable cause) {
        return cause instanceof MarketDataException m && m.kind() == MarketDataException.Kind.RATE_LIMITED;
    }

    /**
     * Two independent flush triggers for the rate-limit-skip summary WARN, either of which
     * fires it — every skip is counted silently (DEBUG-only) otherwise:
     * <ul>
     *   <li><b>Time</b>: a skip that lands ON OR AFTER the window has elapsed flushes a summary
     *       for the just-closed window, then opens a fresh window (this skip counted in it).</li>
     *   <li><b>Size</b>: independently of the window's start time, once the accumulated count
     *       since the last flush reaches {@link #RATE_LIMIT_WARN_BURST_THRESHOLD}, it flushes
     *       immediately and resets only the counter (the window's start time is untouched) —
     *       this is what makes a bounded-batch-run burst self-reporting even when the run ends
     *       before the window would ever elapse on its own.</li>
     * </ul>
     * A lone, isolated skip therefore never produces a WARN by itself — only sustained skipping
     * that crosses one of the two thresholds does.
     * Synchronized because concurrent {@link #aggregate} calls (different symbols) can race on
     * the same provider's window state; contention is negligible since this only runs on the
     * rate-limited-cooldown path.
     */
    private synchronized void recordRateLimitSkip(String providerId) {
        long nowMs = clock.getAsLong();
        Long windowStart = rateLimitWindowStartMs.get(providerId);

        if (windowStart != null && nowMs - windowStart >= RATE_LIMIT_WARN_WINDOW_MS) {
            int skippedInClosedWindow = rateLimitSkipCount.getOrDefault(providerId, 0);
            log.warn("news provider {} rate-limited (cooldown): skipped {} symbol lookup(s) in the last {} ms",
                    providerId, skippedInClosedWindow, RATE_LIMIT_WARN_WINDOW_MS);
            rateLimitWindowStartMs.put(providerId, nowMs);
            rateLimitSkipCount.put(providerId, 1);
            return;
        }

        if (windowStart == null) rateLimitWindowStartMs.put(providerId, nowMs);
        int count = rateLimitSkipCount.merge(providerId, 1, Integer::sum);
        if (count >= RATE_LIMIT_WARN_BURST_THRESHOLD) {
            log.warn("news provider {} rate-limited (cooldown): skipped {} symbol lookup(s) (burst threshold reached)",
                    providerId, count);
            rateLimitSkipCount.put(providerId, 0);
        }
    }

    /** URL-dedup first (blank/unparseable URLs excluded from it), then title-dedup;
     *  first hit wins in provider order. */
    private static List<NewsItem> merge(List<List<NewsItem>> results) {
        Set<String> seenUrls = new HashSet<>();
        Set<String> seenTitles = new HashSet<>();
        List<NewsItem> merged = new ArrayList<>();
        for (List<NewsItem> providerItems : results) {
            if (providerItems == null) continue;
            for (NewsItem n : providerItems) {
                String urlKey = normalizeUrl(n.url());
                String titleKey = normalizeTitle(n.headline());
                if (urlKey != null && seenUrls.contains(urlKey)) continue;
                if (seenTitles.contains(titleKey)) continue;
                if (urlKey != null) seenUrls.add(urlKey);
                seenTitles.add(titleKey);
                merged.add(n);
            }
        }
        return merged;
    }

    /** scheme + lowercase host + path; query stripped (tracking params like ?.tsrc=rss).
     *  Null for blank/unparseable URLs — those take part in title-dedup only. */
    static String normalizeUrl(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI u = new URI(url.trim());
            if (u.getScheme() == null || u.getHost() == null) return null;
            return u.getScheme().toLowerCase(Locale.ROOT) + "://"
                    + u.getHost().toLowerCase(Locale.ROOT)
                    + (u.getPath() == null ? "" : u.getPath());
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static NewsItem withDomain(NewsItem n) {
        return new NewsItem(n.headline(), n.summary(), n.source(), n.sourceType(),
                n.datetime(), n.url(), deriveDomain(n.url()));
    }

    /** Lowercase URL host with a leading {@code www.} prefix stripped; null for
     *  null/blank/unparsable URLs or URLs without a host — never an error (T1.4). */
    static String deriveDomain(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            String host = new URI(url.trim()).getHost();
            if (host == null) return null;
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith("www.") ? host.substring("www.".length()) : host;
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    static String normalizeTitle(String headline) {
        return (headline == null ? "" : headline).toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ").trim();
    }

    /** Case-insensitive; unknown values ignored with a warning; null/empty (or only-unknown) = all. */
    private static Set<String> normalizeSourceTypes(Set<String> sourceTypes, List<String> warnings) {
        if (sourceTypes == null || sourceTypes.isEmpty()) return null;
        Set<String> wanted = new LinkedHashSet<>();
        for (String s : sourceTypes) {
            if (s == null || s.isBlank()) continue;
            String norm = s.toLowerCase(Locale.ROOT);
            if (KNOWN_SOURCE_TYPES.contains(norm)) wanted.add(norm);
            else warnings.add("sourceTypes: unknown value '" + s + "' ignored");
        }
        return wanted.isEmpty() ? null : wanted;
    }

    /**
     * Client-safe warning per failed provider. MarketDataException messages are
     * provider-constructed and already sanitized (ProviderErrors.categorize or static
     * strings). Anything else gets only a category — never raw {@code getMessage()},
     * which can embed request URIs carrying API keys (see ProviderErrors contract).
     */
    private static String warningFor(NewsProvider p, Throwable cause) {
        if (cause instanceof MarketDataException m && m.getMessage() != null)
            return prefixWithProviderId(p.id(), m.getMessage());
        for (Throwable t = cause; t != null; t = t.getCause())
            if (t instanceof SocketTimeoutException) return p.id() + ": timeout";
        return p.id() + ": request failed";
    }

    /** Avoids a doubled provider-id prefix when the message already carries it
     *  (e.g. providers that route through {@code ProviderErrors.categorize(id(), e)}). */
    private static String prefixWithProviderId(String id, String message) {
        if (message.equals(id) || message.startsWith(id + ":") || message.startsWith(id + " "))
            return message;
        return id + ": " + message;
    }
}
