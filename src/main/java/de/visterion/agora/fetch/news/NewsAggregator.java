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
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    private static final Logger log = LoggerFactory.getLogger(NewsAggregator.class);
    private static final Set<String> KNOWN_SOURCE_TYPES = Set.of("news", "social");

    /** Merged, deduped, sorted, capped items plus one sanitized warning per degraded provider. */
    public record AggregatedNews(List<NewsItem> items, List<String> warnings) {}

    private final List<NewsProvider> providers;
    private final int maxItems;
    private final long budgetMs;

    public NewsAggregator(List<NewsProvider> providers, int maxItems) {
        this(providers, maxItems, TOTAL_BUDGET_MS);
    }

    NewsAggregator(List<NewsProvider> providers, int maxItems, long budgetMs) {
        this.providers = List.copyOf(providers);
        this.maxItems = maxItems;
        this.budgetMs = budgetMs;
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
                    warnings.add(warningFor(p, e.getCause() == null ? e : e.getCause()));
                    log.warn("news provider {} failed for {}", p.id(), symbol, e.getCause());
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
