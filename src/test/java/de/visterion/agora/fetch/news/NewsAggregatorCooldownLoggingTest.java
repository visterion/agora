package de.visterion.agora.fetch.news;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.visterion.agora.data.MarketDataException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A single genuine 429 upstream (real transport failure, logged once at WARN by
 * RssNewsProvider itself) must not turn into one WARN per subsequent symbol whose feed hits
 * the same host's cooldown. NewsAggregator sees every one of those cooldown rejections as a
 * per-symbol provider failure and used to log all of them at WARN (NewsAggregator.java:121) —
 * this pins the fix: cooldown rejections are typed (Kind.RATE_LIMITED), demoted to DEBUG, and
 * summarized in at most one time-windowed WARN per provider.
 */
class NewsAggregatorCooldownLoggingTest {

    private static final LocalDate FROM = LocalDate.parse("2026-07-10");
    private static final LocalDate TO = LocalDate.parse("2026-07-16");

    private ListAppender<ILoggingEvent> logs;
    private Logger aggregatorLogger;

    @BeforeEach void attachLogAppender() {
        aggregatorLogger = (Logger) LoggerFactory.getLogger(NewsAggregator.class);
        aggregatorLogger.setLevel(Level.DEBUG);
        logs = new ListAppender<>();
        logs.start();
        aggregatorLogger.addAppender(logs);
    }

    @AfterEach void detachLogAppender() {
        aggregatorLogger.detachAppender(logs);
    }

    private static NewsProvider ok(String id, NewsItem item) {
        return new NewsProvider() {
            @Override public String id() { return id; }
            @Override public boolean configured() { return true; }
            @Override public List<NewsItem> companyNews(String s, LocalDate f, LocalDate t) { return List.of(item); }
        };
    }

    /** Mimics RssNewsProvider.fetch's cooldown-skip path: throws the same message every call. */
    private static NewsProvider cooldownRejecting(String id) {
        return new NewsProvider() {
            @Override public String id() { return id; }
            @Override public boolean configured() { return true; }
            @Override public List<NewsItem> companyNews(String s, LocalDate f, LocalDate t) {
                throw new MarketDataException(MarketDataException.Kind.RATE_LIMITED,
                        id + " rate limited (cooldown)", null);
            }
        };
    }

    private static NewsItem item(String headline, String url) {
        return new NewsItem(headline, "", "src", "social", null, url);
    }

    private List<ILoggingEvent> warnEvents() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    private List<ILoggingEvent> debugEvents() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.DEBUG).toList();
    }

    // ---- (a) cooldown rejection produces no WARN ----

    @Test void cooldownRejectionProducesNoWarnOnlyDebug() {
        NewsAggregator agg = new NewsAggregator(List.of(
                ok("finnhub", item("a", "https://x/1")),
                cooldownRejecting("rss:reddit-stocks")), 200);

        agg.aggregate("AAPL", FROM, TO, Set.of());

        assertThat(warnEvents()).isEmpty();
        assertThat(debugEvents()).anySatisfy(e ->
                assertThat(e.getFormattedMessage()).contains("rss:reddit-stocks"));
    }

    // ---- (b) AggregatedNews.warnings stays byte-identical ----

    @Test void agentVisibleWarningsAreByteIdenticalToTodaysContent() {
        NewsAggregator agg = new NewsAggregator(List.of(
                ok("finnhub", item("a", "https://x/1")),
                cooldownRejecting("rss:reddit-stocks")), 200);

        var result = agg.aggregate("AAPL", FROM, TO, Set.of());

        // Same shape as the pre-existing non-rate-limited failure path
        // (NewsAggregatorTest#partialFailureYieldsOtherItemsPlusOneWarningPerFailedProvider):
        // the raw provider message, unprefixed since it already carries the provider id.
        assertThat(result.warnings()).containsExactly("rss:reddit-stocks rate limited (cooldown)");
        assertThat(result.items()).extracting(NewsItem::headline).containsExactly("a");
    }

    // ---- (c) at most one WARN per provider per time window ----

    @Test void atMostOneWarnPerProviderPerTimeWindowSummarizingTheClosedWindow() {
        AtomicLong clock = new AtomicLong(0L);
        NewsProvider rateLimited = cooldownRejecting("rss:reddit-stocks");
        NewsAggregator agg = new NewsAggregator(
                List.of(ok("finnhub", item("a", "https://x/1")), rateLimited),
                200, NewsAggregator.TOTAL_BUDGET_MS, clock::get);

        // Three symbol lookups inside the same window: window is open, nothing has elapsed yet
        // to flush — no WARN, only the per-skip DEBUG line (this also covers case (a): a lone
        // cooldown rejection, in isolation, never produces a WARN by itself).
        agg.aggregate("AAPL", FROM, TO, Set.of());
        agg.aggregate("MSFT", FROM, TO, Set.of());
        agg.aggregate("GOOG", FROM, TO, Set.of());
        assertThat(warnEvents()).isEmpty();
        assertThat(debugEvents()).hasSize(3);

        // Advance past the window: the next skip flushes exactly one summary WARN for the
        // three skips that happened in the window that just closed.
        clock.addAndGet(NewsAggregator.RATE_LIMIT_WARN_WINDOW_MS);
        agg.aggregate("TSLA", FROM, TO, Set.of());
        assertThat(warnEvents()).hasSize(1);
        assertThat(warnEvents().get(0).getFormattedMessage())
                .contains("rss:reddit-stocks").contains("3");

        // Further skips within the new window: still no additional WARN.
        agg.aggregate("NVDA", FROM, TO, Set.of());
        assertThat(warnEvents()).hasSize(1);

        // Advance past the window again: exactly one more summary WARN (for TSLA + NVDA = 2).
        clock.addAndGet(NewsAggregator.RATE_LIMIT_WARN_WINDOW_MS);
        agg.aggregate("AMD", FROM, TO, Set.of());
        assertThat(warnEvents()).hasSize(2);
        assertThat(warnEvents().get(1).getFormattedMessage())
                .contains("rss:reddit-stocks").contains("2");
    }

    // ---- burst threshold: the gap the time window alone cannot cover ----
    //
    // Agora's workload is bounded batch runs, not a continuous stream: a genuine 429 at
    // symbol #5 of a 145-symbol run skips the remaining ~140 inside ONE run that then ends,
    // entirely inside one RATE_LIMIT_WARN_WINDOW_MS window, with no further call afterwards to
    // ever trigger the time-based flush. These three tests pin the size-based second trigger
    // that exists specifically to cover that gap.

    @Test void burstReachingThresholdWithNoFurtherCallsFlushesExactlyOnceWithTheCount() {
        AtomicLong clock = new AtomicLong(0L); // clock never advances: this is the "run ends, no further calls" case
        NewsAggregator agg = new NewsAggregator(
                List.of(ok("finnhub", item("a", "https://x/1")), cooldownRejecting("rss:reddit-stocks")),
                200, NewsAggregator.TOTAL_BUDGET_MS, clock::get);

        for (int i = 0; i < NewsAggregator.RATE_LIMIT_WARN_BURST_THRESHOLD; i++)
            agg.aggregate("SYM" + i, FROM, TO, Set.of());

        // The motivating incident (~140 skips inside one run) must be self-reporting even
        // though the window never elapses — this is the primary risk this fix addresses.
        assertThat(warnEvents()).hasSize(1);
        assertThat(warnEvents().get(0).getFormattedMessage())
                .contains("rss:reddit-stocks")
                .contains(String.valueOf(NewsAggregator.RATE_LIMIT_WARN_BURST_THRESHOLD));
        assertThat(debugEvents()).hasSize(NewsAggregator.RATE_LIMIT_WARN_BURST_THRESHOLD);
    }

    @Test void burstBelowThresholdWithNoFurtherCallsStillProducesNoWarn() {
        AtomicLong clock = new AtomicLong(0L); // never advances, and never reaches the burst threshold either
        NewsAggregator agg = new NewsAggregator(
                List.of(ok("finnhub", item("a", "https://x/1")), cooldownRejecting("rss:reddit-stocks")),
                200, NewsAggregator.TOTAL_BUDGET_MS, clock::get);

        int belowThreshold = NewsAggregator.RATE_LIMIT_WARN_BURST_THRESHOLD - 1;
        for (int i = 0; i < belowThreshold; i++)
            agg.aggregate("SYM" + i, FROM, TO, Set.of());

        // The quiet property must survive: staying under the threshold (and never reaching the
        // time window either) means still zero WARNs — only DEBUG per skip.
        assertThat(warnEvents()).isEmpty();
        assertThat(debugEvents()).hasSize(belowThreshold);
    }

    @Test void crossingThresholdMidBurstFlushesOnceNotOncePerSubsequentSkip() {
        AtomicLong clock = new AtomicLong(0L);
        NewsAggregator agg = new NewsAggregator(
                List.of(ok("finnhub", item("a", "https://x/1")), cooldownRejecting("rss:reddit-stocks")),
                200, NewsAggregator.TOTAL_BUDGET_MS, clock::get);

        // Overshoot the threshold by 5 skips, all in the same burst, no time advance: crossing
        // it must flush exactly once, not once for every skip from the crossing point onward.
        int burstSize = NewsAggregator.RATE_LIMIT_WARN_BURST_THRESHOLD + 5;
        for (int i = 0; i < burstSize; i++)
            agg.aggregate("SYM" + i, FROM, TO, Set.of());

        assertThat(warnEvents()).hasSize(1);
        assertThat(warnEvents().get(0).getFormattedMessage())
                .contains(String.valueOf(NewsAggregator.RATE_LIMIT_WARN_BURST_THRESHOLD));
        assertThat(debugEvents()).hasSize(burstSize);
    }
}
