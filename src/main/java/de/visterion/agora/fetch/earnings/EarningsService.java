package de.visterion.agora.fetch.earnings;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.TtlCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Earnings calendar merged across providers, with a three-valued outcome.
 *
 * <p>Providers are queried in parallel under one total budget and their results merged, rather
 * than the old first-success chain. The chain treated an empty result as "no answer" and fell
 * through to the next provider, so a correct "this symbol has no upcoming earnings" turned into
 * an UNAVAILABLE the moment the fallback was down — and, because {@code TtlCache} never stores a
 * throwing load, it re-failed on every single call.
 *
 * <p>An answer is <em>complete</em> when every provider needed for the window either succeeded or
 * was unnecessary; <em>partial</em> when a needed one failed, cooled or timed out; and an outright
 * failure only when nothing usable answered at all. Complete answers cache for the full TTL,
 * partial ones for a short one — long enough to stop hammering, short enough not to poison a session.
 */
@Component
public class EarningsService {

    private static final Logger log = LoggerFactory.getLogger(EarningsService.class);
    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("America/New_York");

    private final List<EarningsProvider> providers;
    private final TtlCache<String, EarningsResult> completeCache;
    private final TtlCache<String, EarningsResult> partialCache;
    private final ProviderCooldown cooldown;
    private final long budgetMs;
    private final Supplier<LocalDate> todayEt;

    @Autowired
    public EarningsService(List<EarningsProvider> providers,
                           @Value("${agora.data.cache.ttl.fundamentals-seconds:21600}") long ttlSeconds,
                           @Value("${agora.fetch.earnings.partial-ttl-seconds:600}") long partialTtlSeconds,
                           @Value("${agora.fetch.earnings.cooldown-threshold:3}") int cooldownThreshold,
                           @Value("${agora.fetch.earnings.cooldown-ms:600000}") long cooldownMs,
                           @Value("${agora.fetch.earnings.budget-ms:7000}") long budgetMs) {
        this(providers, ttlSeconds, partialTtlSeconds, cooldownThreshold, cooldownMs, budgetMs,
                System::currentTimeMillis, () -> LocalDate.now(EXCHANGE_ZONE));
    }

    EarningsService(List<EarningsProvider> providers, long ttlSeconds, long partialTtlSeconds,
                    int cooldownThreshold, long cooldownMs, long budgetMs,
                    LongSupplier now, Supplier<LocalDate> todayEt) {
        this.providers = List.copyOf(providers);
        this.completeCache = new TtlCache<>(ttlSeconds * 1000L, 4096, now);
        this.partialCache = new TtlCache<>(partialTtlSeconds * 1000L, 4096, now);
        this.cooldown = new ProviderCooldown(cooldownThreshold, cooldownMs, now);
        this.budgetMs = budgetMs;
        this.todayEt = todayEt;
    }

    public EarningsResult earnings(String symbol, LocalDate from, LocalDate to) {
        String key = "earn:" + symbol.toUpperCase() + ":" + from + ":" + to;
        return cached(key, () -> collect(symbol, from, to));
    }

    /** Market-wide earnings for the window (no symbol). Distinct cache family from symbol lookups. */
    public EarningsResult earningsWindow(LocalDate from, LocalDate to) {
        String key = "earnwin:" + from + ":" + to;
        return cached(key, () -> collect(null, from, to));
    }

    /**
     * Complete cache wins over partial, so a stale partial can never shadow a fresher complete
     * answer; storing a complete result drops any partial entry for the same key.
     */
    private EarningsResult cached(String key, Supplier<EarningsResult> loader) {
        var complete = completeCache.peek(key);
        if (complete.isPresent()) return complete.get();
        var partial = partialCache.peek(key);
        if (partial.isPresent()) return partial.get();

        EarningsResult r = loader.get();
        if (r.partial()) partialCache.put(key, r);
        else { completeCache.put(key, r); partialCache.remove(key); }
        return r;
    }

    private EarningsResult collect(String symbol, LocalDate from, LocalDate to) {
        LocalDate today = todayEt.get();
        List<EarningsProvider> relevant = new ArrayList<>();
        for (EarningsProvider p : providers)
            if (p.coverage().covers(from, to, today)) relevant.add(p);

        if (relevant.isEmpty())
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "no provider covers the requested earnings window", null);

        // Phase 1: every relevant provider except Yahoo's market-wide pager. Phase 2 (below)
        // consults Yahoo only for what phase 1 left uncovered — and only from its page cache,
        // never by crawling inline (D17: the crawl cannot finish inside this budget).
        List<EarningsProvider> phase1 = new ArrayList<>();
        YahooEarningsProvider yahoo = null;
        for (EarningsProvider p : relevant) {
            if (p instanceof YahooEarningsProvider y) yahoo = y;
            else phase1.add(p);
        }

        List<List<EarningsEvent>> results = new ArrayList<>();
        boolean anySuccess = false;
        boolean degraded = false;

        if (!phase1.isEmpty()) {
            long deadlineNanos = System.nanoTime() + budgetMs * 1_000_000L;
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<List<EarningsEvent>>> futures = new ArrayList<>();
                List<EarningsProvider> submitted = new ArrayList<>();
                for (EarningsProvider p : phase1) {
                    if (cooldown.isCooled(p)) { degraded = true; continue; }
                    submitted.add(p);
                    futures.add(pool.submit(() -> p.earnings(symbol, from, to)));
                }
                for (int i = 0; i < submitted.size(); i++) {
                    EarningsProvider p = submitted.get(i);
                    long remaining = deadlineNanos - System.nanoTime();
                    try {
                        results.add(futures.get(i).get(Math.max(remaining, 0L), TimeUnit.NANOSECONDS));
                        cooldown.recordSuccess(p);
                        anySuccess = true;
                    } catch (TimeoutException e) {
                        // Budget cancellation is NOT a provider failure: a healthy-but-slow source
                        // must not accumulate cooldown strikes. Hangs are caught by the per-attempt
                        // timeout instead, which surfaces as a real ExecutionException below.
                        futures.get(i).cancel(true);
                        degraded = true;
                    } catch (ExecutionException e) {
                        cooldown.recordFailure(p);
                        degraded = true;
                        log.debug("earnings provider {} failed for {}", p.name(), symbol, e.getCause());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                                "earnings collection interrupted", e);
                    }
                }
                pool.shutdownNow();
            }
        }

        List<EarningsEvent> merged = EarningsMerger.merge(results);
        boolean uncovered = merged.isEmpty();

        // Phase 2: Yahoo is a healthy-path skip — it is only ever consulted (from its page
        // cache, read-only) when phase 1 left the window uncovered, so a symbol phase 1 already
        // answered never pays for Yahoo at all.
        if (yahoo != null && uncovered && !cooldown.isCooled(yahoo)) {
            Optional<List<EarningsEvent>> page = yahoo.window(from, to);
            if (page.isPresent()) {
                anySuccess = true;
                List<EarningsEvent> filtered = symbol == null ? page.get()
                        : page.get().stream()
                              .filter(e -> e.symbol().equalsIgnoreCase(symbol)).toList();
                merged = EarningsMerger.merge(List.of(merged, filtered));
            } else {
                degraded = true;   // cache still warming — we could not see Yahoo this call
            }
        }

        if (!anySuccess)
            throw new MarketDataException(MarketDataException.Kind.UNAVAILABLE,
                    "no earnings provider could serve " + (symbol == null ? "the window" : symbol), null);

        return new EarningsResult(merged, degraded);
    }
}
