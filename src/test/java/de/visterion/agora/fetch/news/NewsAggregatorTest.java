package de.visterion.agora.fetch.news;

import de.visterion.agora.data.MarketDataException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.*;

class NewsAggregatorTest {

    private static final LocalDate FROM = LocalDate.parse("2026-07-10");
    private static final LocalDate TO = LocalDate.parse("2026-07-16");
    private static final Instant T1 = Instant.parse("2026-07-15T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-07-15T12:00:00Z");
    private static final Instant T3 = Instant.parse("2026-07-15T14:00:00Z");

    private static NewsProvider provider(String id, List<NewsItem> items) {
        return new NewsProvider() {
            @Override public String id() { return id; }
            @Override public boolean configured() { return true; }
            @Override public List<NewsItem> companyNews(String s, LocalDate f, LocalDate t) { return items; }
        };
    }

    private static NewsProvider failing(String id, RuntimeException e) {
        return new NewsProvider() {
            @Override public String id() { return id; }
            @Override public boolean configured() { return true; }
            @Override public List<NewsItem> companyNews(String s, LocalDate f, LocalDate t) { throw e; }
        };
    }

    private static NewsProvider unconfigured(String id) {
        return new NewsProvider() {
            @Override public String id() { return id; }
            @Override public boolean configured() { return false; }
            @Override public List<NewsItem> companyNews(String s, LocalDate f, LocalDate t) {
                throw new AssertionError("must never be called");
            }
        };
    }

    private static NewsItem item(String headline, String url, String sourceType, Instant dt) {
        return new NewsItem(headline, "", "src", sourceType, dt, url);
    }

    // ---- dedup ----

    @Test void dedupsByNormalizedUrlBeforeTitleQueryStringStripped() {
        NewsProvider finnhub = provider("finnhub",
                List.of(item("Finnhub wording", "https://Finance.Yahoo.com/news/a.html", "news", T1)));
        NewsProvider yahoo = provider("rss:yahoo-rss",
                List.of(item("Yahoo wording differs", "https://finance.yahoo.com/news/a.html?.tsrc=rss", "news", T1)));
        var result = new NewsAggregator(List.of(finnhub, yahoo), 200).aggregate("AAPL", FROM, TO, Set.of());
        // Same normalized URL (query stripped, host lowercased) → one item; finnhub (first) wins.
        assertThat(result.items()).extracting(NewsItem::headline).containsExactly("Finnhub wording");
    }

    @Test void dedupsByNormalizedTitleWhenUrlsDiffer() {
        NewsProvider finnhub = provider("finnhub",
                List.of(item("Apple  Beats Estimates", "https://a.com/1", "news", T1)));
        NewsProvider yahoo = provider("rss:yahoo-rss",
                List.of(item("apple beats estimates", "https://b.com/2", "news", T1)));
        var result = new NewsAggregator(List.of(finnhub, yahoo), 200).aggregate("AAPL", FROM, TO, Set.of());
        assertThat(result.items()).extracting(NewsItem::headline).containsExactly("Apple  Beats Estimates");
    }

    @Test void blankUrlItemsDoNotDedupeEachOtherByUrl() {
        NewsProvider finnhub = provider("finnhub", List.of(
                item("First urlless story", "", "news", T1),
                item("Second urlless story", "", "news", T2)));
        var result = new NewsAggregator(List.of(finnhub), 200).aggregate("AAPL", FROM, TO, Set.of());
        assertThat(result.items()).hasSize(2);
    }

    @Test void providerOrderIsDeterministicFinnhubWinsDedup() {
        NewsItem fromFeed = item("Shared story", "https://x.com/s", "news", T1);
        NewsItem fromFinnhub = new NewsItem("Shared story", "finnhub summary", "Reuters", "news", T1, "https://x.com/s");
        var result = new NewsAggregator(List.of(
                provider("finnhub", List.of(fromFinnhub)),
                provider("rss:yahoo-rss", List.of(fromFeed))), 200)
                .aggregate("AAPL", FROM, TO, Set.of());
        assertThat(result.items()).containsExactly(fromFinnhub);
    }

    // ---- sort + cap ----

    @Test void sortsDatetimeDescendingNullsLast() {
        NewsProvider p = provider("finnhub", List.of(
                item("old", "https://x/1", "news", T1),
                item("dateless", "https://x/2", "news", null),
                item("new", "https://x/3", "news", T3),
                item("mid", "https://x/4", "news", T2)));
        var result = new NewsAggregator(List.of(p), 200).aggregate("AAPL", FROM, TO, Set.of());
        assertThat(result.items()).extracting(NewsItem::headline)
                .containsExactly("new", "mid", "old", "dateless");
    }

    @Test void capsAtMaxItems() {
        List<NewsItem> many = new ArrayList<>();
        for (int i = 0; i < 201; i++) many.add(item("h" + i, "https://x/" + i, "news", T1.plusSeconds(i)));
        var result = new NewsAggregator(List.of(provider("finnhub", many)), 200)
                .aggregate("AAPL", FROM, TO, Set.of());
        assertThat(result.items()).hasSize(200);
    }

    @Test void sourceTypesFilterRunsBeforeCapSoNewsSurvivesSocialFlood() {
        List<NewsItem> social = new ArrayList<>();
        for (int i = 0; i < 200; i++)
            social.add(item("post " + i, "https://s/" + i, "social", T3.plusSeconds(i))); // newest
        List<NewsItem> news = new ArrayList<>();
        for (int i = 0; i < 50; i++)
            news.add(item("article " + i, "https://n/" + i, "news", T1.plusSeconds(i))); // older
        NewsAggregator agg = new NewsAggregator(List.of(
                provider("finnhub", news), provider("rss:reddit-stocks", social)), 200);
        var all = agg.aggregate("AAPL", FROM, TO, Set.of());
        assertThat(all.items()).hasSize(200); // capped, social-newest dominates
        var newsOnly = agg.aggregate("AAPL", FROM, TO, Set.of("news"));
        assertThat(newsOnly.items()).hasSize(50);
        assertThat(newsOnly.items()).allSatisfy(n -> assertThat(n.sourceType()).isEqualTo("news"));
    }

    // ---- sourceTypes semantics ----

    @Test void emptySourceTypesMeansAll() {
        NewsAggregator agg = new NewsAggregator(List.of(provider("finnhub", List.of(
                item("a", "https://x/1", "news", T1), item("b", "https://x/2", "social", T2)))), 200);
        assertThat(agg.aggregate("AAPL", FROM, TO, Set.of()).items()).hasSize(2);
        assertThat(agg.aggregate("AAPL", FROM, TO, null).items()).hasSize(2);
    }

    @Test void sourceTypesMatchingIsCaseInsensitive() {
        NewsAggregator agg = new NewsAggregator(List.of(provider("finnhub", List.of(
                item("a", "https://x/1", "news", T1), item("b", "https://x/2", "social", T2)))), 200);
        var result = agg.aggregate("AAPL", FROM, TO, Set.of("NEWS"));
        assertThat(result.items()).extracting(NewsItem::headline).containsExactly("a");
        assertThat(result.warnings()).isEmpty();
    }

    @Test void unknownSourceTypeIsIgnoredWithWarning() {
        NewsAggregator agg = new NewsAggregator(List.of(provider("finnhub", List.of(
                item("a", "https://x/1", "news", T1), item("b", "https://x/2", "social", T2)))), 200);
        var result = agg.aggregate("AAPL", FROM, TO, Set.of("news", "video"));
        assertThat(result.items()).extracting(NewsItem::headline).containsExactly("a");
        assertThat(result.warnings()).contains("sourceTypes: unknown value 'video' ignored");
        // Only unknown values → falls back to all
        var onlyUnknown = agg.aggregate("AAPL", FROM, TO, Set.of("video"));
        assertThat(onlyUnknown.items()).hasSize(2);
    }

    // ---- partial failure / warnings hygiene ----

    @Test void partialFailureYieldsOtherItemsPlusOneWarningPerFailedProvider() {
        NewsProvider ok = provider("finnhub", List.of(item("a", "https://x/1", "news", T1)));
        NewsProvider bad = failing("rss:yahoo-rss",
                new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "rss:yahoo-rss timeout", null));
        var result = new NewsAggregator(List.of(ok, bad), 200).aggregate("AAPL", FROM, TO, Set.of());
        assertThat(result.items()).hasSize(1);
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().get(0)).isEqualTo("rss:yahoo-rss timeout");
    }

    @Test void warningDoesNotDoublePrefixWhenMessageAlreadyCarriesProviderId() {
        NewsProvider ok = provider("finnhub", List.of(item("a", "https://x/1", "news", T1)));
        NewsProvider bad = failing("rss:yahoo-rss",
                new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "rss:yahoo-rss timeout", null));
        var result = new NewsAggregator(List.of(ok, bad), 200).aggregate("AAPL", FROM, TO, Set.of());
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().get(0)).isEqualTo("rss:yahoo-rss timeout");
    }

    @Test void warningsNeverContainRawExceptionText() {
        NewsProvider ok = provider("finnhub", List.of(item("a", "https://x/1", "news", T1)));
        NewsProvider bad = failing("rss:evil",
                new RuntimeException("GET https://api.example.com/feed?token=SECRET123 failed"));
        var result = new NewsAggregator(List.of(ok, bad), 200).aggregate("AAPL", FROM, TO, Set.of());
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().get(0)).doesNotContain("SECRET123");
        assertThat(result.warnings().get(0)).isEqualTo("rss:evil: request failed");
    }

    @Test void unconfiguredProvidersAreSkippedSilently() {
        var result = new NewsAggregator(List.of(
                unconfigured("finnhub"),
                provider("rss:yahoo-rss", List.of(item("a", "https://x/1", "news", T1)))), 200)
                .aggregate("AAPL", FROM, TO, Set.of());
        assertThat(result.items()).hasSize(1);
        assertThat(result.warnings()).isEmpty();
    }

    // ---- total failure ----

    @Test void allProvidersFailingThrowsUnavailableWithCombinedMessage() {
        assertThatThrownBy(() -> new NewsAggregator(List.of(
                failing("finnhub", new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "finnhub: no api key", null)),
                failing("rss:yahoo-rss", new RuntimeException("boom"))), 200)
                .aggregate("AAPL", FROM, TO, Set.of()))
                .isInstanceOfSatisfying(MarketDataException.class, e -> {
                    assertThat(e.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE);
                    assertThat(e.getMessage()).contains("finnhub").contains("rss:yahoo-rss");
                });
    }

    @Test void noConfiguredProviderThrowsUnavailable() {
        assertThatThrownBy(() -> new NewsAggregator(List.of(unconfigured("finnhub")), 200)
                .aggregate("AAPL", FROM, TO, Set.of()))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE));
        assertThatThrownBy(() -> new NewsAggregator(List.of(), 200)
                .aggregate("AAPL", FROM, TO, Set.of()))
                .isInstanceOf(MarketDataException.class);
    }

    // ---- budget ----

    @Test void budgetConstantStaysBelowConsumerMcpTimeouts() {
        // Pins the fan-out budget: it must stay below consumer MCP client timeouts so a slow
        // feed degrades into a partial result, never a total timeout at the consumer.
        assertThat(NewsAggregator.TOTAL_BUDGET_MS).isEqualTo(7000L);
    }

    @Test void hangingProviderIsDroppedAtBudgetWithoutStallingAggregate() {
        CountDownLatch release = new CountDownLatch(1);
        NewsProvider hanging = new NewsProvider() {
            @Override public String id() { return "rss:slow"; }
            @Override public boolean configured() { return true; }
            @Override public List<NewsItem> companyNews(String s, LocalDate f, LocalDate t) {
                try {
                    release.await(); // blocks until interrupted by cancel(true)/shutdownNow
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return List.of();
            }
        };
        NewsProvider fast = provider("finnhub", List.of(item("a", "https://x/1", "news", T1)));
        // 150 ms test budget via the package-private ctor — no sleeps in the test itself.
        NewsAggregator agg = new NewsAggregator(List.of(fast, hanging), 200, 150);
        long start = System.nanoTime();
        var result = agg.aggregate("AAPL", FROM, TO, Set.of());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        release.countDown(); // cleanup either way
        assertThat(result.items()).hasSize(1);
        assertThat(result.warnings()).anySatisfy(w -> assertThat(w).startsWith("rss:slow:"));
        // Wall-clock proof: the hanging provider must not stall the aggregate past the budget
        // (generous ceiling for CI jitter, still far below any provider timeout).
        assertThat(elapsedMs).isLessThan(5_000L);
    }
}
