package de.visterion.agora.fetch.news;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.agora.data.MarketDataException;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class RssNewsProviderTest {

    /** Realistic Yahoo Finance RSS 2.0 shape (rss > channel > item; pubDate RFC-1123). */
    private static final String YAHOO_RSS = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0">
          <channel>
            <title>Yahoo! Finance: AAPL News</title>
            <link>http://finance.yahoo.com/q/h?s=AAPL</link>
            <description>Latest Financial News for AAPL</description>
            <language>en-US</language>
            <item>
              <title>Apple Announces Record Services Revenue</title>
              <description>Apple Inc. reported record services revenue in its latest quarter.</description>
              <link>https://finance.yahoo.com/news/apple-announces-record-services-203500123.html?.tsrc=rss</link>
              <pubDate>Wed, 15 Jul 2026 20:35:00 +0000</pubDate>
              <guid isPermaLink="false">apple-announces-record-services-203500123.html</guid>
            </item>
            <item>
              <title>Apple &amp; Suppliers Rally After &lt;Strong&gt; Guidance</title>
              <description>&lt;p&gt;Shares of Apple and suppliers moved higher.&lt;/p&gt;</description>
              <link>https://finance.yahoo.com/news/apple-suppliers-rally-181200456.html?.tsrc=rss</link>
              <pubDate>Wed, 15 Jul 2026 18:12:00 +0000</pubDate>
              <guid isPermaLink="false">apple-suppliers-rally-181200456.html</guid>
            </item>
            <item>
              <title>Dateless Wire Item</title>
              <description>No pubDate on this one.</description>
              <link>https://finance.yahoo.com/news/dateless-item-000000789.html?.tsrc=rss</link>
            </item>
          </channel>
        </rss>
        """;

    /** Realistic Reddit search Atom shape (feed > entry; updated ISO-8601; content type="html"). */
    private static final String REDDIT_ATOM = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xmlns:media="http://search.yahoo.com/mrss/">
          <category term="stocks" label="r/stocks"/>
          <updated>2026-07-16T09:15:30+00:00</updated>
          <title>search results for 'AAPL', ordered by newest</title>
          <entry>
            <author><name>/u/example_trader</name><uri>https://old.reddit.com/user/example_trader</uri></author>
            <category term="stocks" label="r/stocks"/>
            <content type="html">&lt;!-- SC_OFF --&gt;&lt;div class="md"&gt;&lt;p&gt;Thoughts on AAPL earnings next week? Calls or puts?&lt;/p&gt;&lt;/div&gt;&lt;!-- SC_ON --&gt;</content>
            <id>t3_1abcd2</id>
            <link href="https://old.reddit.com/r/stocks/comments/1abcd2/aapl_earnings_next_week/"/>
            <updated>2026-07-16T09:15:30+00:00</updated>
            <title>AAPL earnings next week — what are you expecting?</title>
          </entry>
          <entry>
            <author><name>/u/value_hunter</name><uri>https://old.reddit.com/user/value_hunter</uri></author>
            <content type="html">&lt;div class="md"&gt;&lt;p&gt;Long thread about services growth.&lt;/p&gt;&lt;/div&gt;</content>
            <id>t3_1abcd3</id>
            <link href="https://old.reddit.com/r/stocks/comments/1abcd3/aapl_services_growth/"/>
            <updated>2026-07-15T22:01:12+00:00</updated>
            <title>AAPL services growth is underrated</title>
          </entry>
        </feed>
        """;

    private static final String XXE_RSS = """
        <?xml version="1.0"?>
        <!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
        <rss version="2.0"><channel><item><title>&xxe;</title>
        <link>https://x/1</link><pubDate>Wed, 15 Jul 2026 20:35:00 +0000</pubDate></item></channel></rss>
        """;

    static WireMockServer wm;
    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() {
        wm.resetAll();
        RssNewsProvider.clearCooldownsForTests();
    }

    private static final LocalDate FROM = LocalDate.parse("2026-07-10");
    private static final LocalDate TO = LocalDate.parse("2026-07-16");
    private static final String DEFAULT_UA = "agora-news/1.0";

    private RssNewsProvider feed(String id, String pathTemplate, String sourceType, long timeoutMs) {
        return new RssNewsProvider(id, wm.baseUrl() + pathTemplate, sourceType,
                timeoutMs, 900L, System::currentTimeMillis, DEFAULT_UA, 0L);
    }

    private static void stubXml(String path, String body) {
        wm.stubFor(get(urlEqualTo(path)).willReturn(aResponse()
                .withHeader("Content-Type", "application/rss+xml; charset=UTF-8").withBody(body)));
    }

    @Test void idIsRssPrefixedAndConfiguredNeedsUrl() {
        assertThat(feed("yahoo-rss", "/rss?s={symbol}", "news", 5000).id()).isEqualTo("rss:yahoo-rss");
        assertThat(feed("yahoo-rss", "/rss?s={symbol}", "news", 5000).configured()).isTrue();
        assertThat(new RssNewsProvider("x", " ", "news", 5000, 900L, System::currentTimeMillis,
                DEFAULT_UA, 0L).configured()).isFalse();
    }

    @Test void parsesYahooRss() {
        stubXml("/rss?s=AAPL", YAHOO_RSS);
        List<NewsItem> items = feed("yahoo-rss", "/rss?s={symbol}", "news", 5000)
                .companyNews("AAPL", FROM, TO);
        assertThat(items).hasSize(3);
        NewsItem first = items.get(0);
        assertThat(first.headline()).isEqualTo("Apple Announces Record Services Revenue");
        assertThat(first.summary()).isEqualTo("Apple Inc. reported record services revenue in its latest quarter.");
        assertThat(first.source()).isEqualTo("yahoo-rss");
        assertThat(first.sourceType()).isEqualTo("news");
        assertThat(first.datetime()).isEqualTo(Instant.parse("2026-07-15T20:35:00Z"));
        assertThat(first.url()).isEqualTo("https://finance.yahoo.com/news/apple-announces-record-services-203500123.html?.tsrc=rss");
    }

    @Test void parsesRedditAtomWithHtmlStrippedSummary() {
        stubXml("/search.rss?q=AAPL", REDDIT_ATOM);
        List<NewsItem> items = feed("reddit-stocks", "/search.rss?q={symbol}", "social", 5000)
                .companyNews("AAPL", FROM, TO);
        assertThat(items).hasSize(2);
        NewsItem first = items.get(0);
        assertThat(first.headline()).isEqualTo("AAPL earnings next week — what are you expecting?");
        assertThat(first.summary()).isEqualTo("Thoughts on AAPL earnings next week? Calls or puts?");
        assertThat(first.summary()).doesNotContain("<").doesNotContain(">");
        assertThat(first.sourceType()).isEqualTo("social");
        assertThat(first.datetime()).isEqualTo(Instant.parse("2026-07-16T09:15:30Z"));
        assertThat(first.url()).isEqualTo("https://old.reddit.com/r/stocks/comments/1abcd2/aapl_earnings_next_week/");
    }

    @Test void decodesXmlEntitiesInTitles() {
        stubXml("/rss?s=AAPL", YAHOO_RSS);
        List<NewsItem> items = feed("yahoo-rss", "/rss?s={symbol}", "news", 5000)
                .companyNews("AAPL", FROM, TO);
        // XML entities decode via the parser ("&amp;" -> "&", "&lt;Strong&gt;" -> "<Strong>");
        // tags are stripped from summaries only, titles keep the decoded literal text.
        assertThat(items.get(1).headline()).isEqualTo("Apple & Suppliers Rally After <Strong> Guidance");
        assertThat(items.get(1).summary()).isEqualTo("Shares of Apple and suppliers moved higher.");
    }

    @Test void missingPubDateYieldsNullDatetimeAndItemKept() {
        stubXml("/rss?s=AAPL", YAHOO_RSS);
        List<NewsItem> items = feed("yahoo-rss", "/rss?s={symbol}", "news", 5000)
                .companyNews("AAPL", FROM, TO);
        assertThat(items.get(2).headline()).isEqualTo("Dateless Wire Item");
        assertThat(items.get(2).datetime()).isNull();
    }

    @Test void urlEncodesSymbolInTemplate() {
        stubXml("/rss?s=BRK.B", YAHOO_RSS);
        stubXml("/rss?s=%5EGSPC", YAHOO_RSS);
        feed("yahoo-rss", "/rss?s={symbol}", "news", 5000).companyNews("BRK.B", FROM, TO);
        feed("yahoo-rss", "/rss?s={symbol}", "news", 5000).companyNews("^GSPC", FROM, TO);
        wm.verify(1, getRequestedFor(urlEqualTo("/rss?s=BRK.B")));
        wm.verify(1, getRequestedFor(urlEqualTo("/rss?s=%5EGSPC")));
    }

    @Test void genericFeedWithoutPlaceholderFiltersByTitleWordToken() {
        String generic = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel>
              <item><title>F is up after earnings</title><link>https://x/1</link>
                <pubDate>Wed, 15 Jul 2026 10:00:00 +0000</pubDate></item>
              <item><title>Fed raises rates again</title><link>https://x/2</link>
                <pubDate>Wed, 15 Jul 2026 11:00:00 +0000</pubDate></item>
              <item><title>BRK.B hits new high</title><link>https://x/3</link>
                <pubDate>Wed, 15 Jul 2026 12:00:00 +0000</pubDate></item>
              <item><title>BRKXB is not a ticker</title><link>https://x/4</link>
                <pubDate>Wed, 15 Jul 2026 13:00:00 +0000</pubDate></item>
            </channel></rss>
            """;
        stubXml("/generic.rss", generic);
        RssNewsProvider p = feed("wire", "/generic.rss", "news", 5000);
        // One-letter ticker: word-token match, not substring ("Fed" must not match "F").
        assertThat(p.companyNews("F", FROM, TO)).extracting(NewsItem::headline)
                .containsExactly("F is up after earnings");
        // Metacharacter symbol: Pattern.quote keeps "." literal ("BRKXB" must not match "BRK.B").
        assertThat(p.companyNews("BRK.B", FROM, TO)).extracting(NewsItem::headline)
                .containsExactly("BRK.B hits new high");
    }

    @Test void appliesUtcDateWindowInclusiveOfFullToDay() {
        String boundary = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel>
              <item><title>Late on to-day</title><link>https://x/1</link>
                <pubDate>Wed, 15 Jul 2026 23:59:00 +0000</pubDate></item>
              <item><title>Day after window</title><link>https://x/2</link>
                <pubDate>Thu, 16 Jul 2026 00:00:00 +0000</pubDate></item>
              <item><title>Before window</title><link>https://x/3</link>
                <pubDate>Thu, 09 Jul 2026 12:00:00 +0000</pubDate></item>
              <item><title>Dateless passes any window</title><link>https://x/4</link></item>
            </channel></rss>
            """;
        stubXml("/rss?s=AAPL", boundary);
        List<NewsItem> items = feed("yahoo-rss", "/rss?s={symbol}", "news", 5000)
                .companyNews("AAPL", LocalDate.parse("2026-07-10"), LocalDate.parse("2026-07-15"));
        assertThat(items).extracting(NewsItem::headline)
                .containsExactly("Late on to-day", "Dateless passes any window");
    }

    @Test void brokenXmlYieldsEmptyList() {
        stubXml("/rss?s=AAPL", "<rss><channel><item></rss");
        assertThat(feed("yahoo-rss", "/rss?s={symbol}", "news", 5000)
                .companyNews("AAPL", FROM, TO)).isEmpty();
    }

    @Test void doctypeIsRejectedYieldingEmptyList() {
        stubXml("/rss?s=AAPL", XXE_RSS);
        assertThat(feed("yahoo-rss", "/rss?s={symbol}", "news", 5000)
                .companyNews("AAPL", FROM, TO)).isEmpty();
    }

    @Test void brokenPubDateYieldsNullDatetimeItemKept() {
        String brokenDate = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel>
              <item><title>Zone-name date</title><link>https://x/1</link>
                <pubDate>Wed, 15 Jul 2026 20:35:00 EST</pubDate></item>
            </channel></rss>
            """;
        stubXml("/rss?s=AAPL", brokenDate);
        List<NewsItem> items = feed("yahoo-rss", "/rss?s={symbol}", "news", 5000)
                .companyNews("AAPL", FROM, TO);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).datetime()).isNull();
    }

    @Test void httpErrorThrowsCategorizedUnavailable() {
        wm.stubFor(get(urlEqualTo("/rss?s=AAPL")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> feed("yahoo-rss", "/rss?s={symbol}", "news", 5000)
                .companyNews("AAPL", FROM, TO))
                .isInstanceOfSatisfying(MarketDataException.class, e -> {
                    assertThat(e.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE);
                    assertThat(e.getMessage()).startsWith("rss:yahoo-rss");
                    assertThat(e.getMessage()).doesNotContain("http"); // no raw URI leakage
                });
    }

    @Test void slowFeedTimesOutAndThrows() {
        wm.stubFor(get(urlEqualTo("/rss?s=AAPL")).willReturn(aResponse()
                .withHeader("Content-Type", "application/rss+xml").withBody(YAHOO_RSS)
                .withFixedDelay(2000)));
        assertThatThrownBy(() -> feed("yahoo-rss", "/rss?s={symbol}", "news", 100)
                .companyNews("AAPL", FROM, TO))
                .isInstanceOf(MarketDataException.class);
    }

    @Test void timeoutOnOneFeedDoesNotAffectSisterInstance() {
        wm.stubFor(get(urlEqualTo("/slow.rss?s=AAPL")).willReturn(aResponse()
                .withBody(YAHOO_RSS).withFixedDelay(2000)));
        stubXml("/fast.rss?s=AAPL", YAHOO_RSS);
        RssNewsProvider slow = feed("slow", "/slow.rss?s={symbol}", "news", 100);
        RssNewsProvider fast = feed("fast", "/fast.rss?s={symbol}", "news", 5000);
        assertThatThrownBy(() -> slow.companyNews("AAPL", FROM, TO)).isInstanceOf(MarketDataException.class);
        assertThat(fast.companyNews("AAPL", FROM, TO)).hasSize(3);
    }

    @Test void cachesWithinTtlMakingNoSecondRequest() {
        stubXml("/rss?s=AAPL", YAHOO_RSS);
        AtomicLong clock = new AtomicLong(1_000_000L);
        RssNewsProvider p = new RssNewsProvider("yahoo-rss", wm.baseUrl() + "/rss?s={symbol}",
                "news", 5000, 900L, clock::get, DEFAULT_UA, 0L);
        p.companyNews("AAPL", FROM, TO);
        clock.addAndGet(899_000L); // still inside the 900 s TTL
        p.companyNews("AAPL", FROM, TO);
        wm.verify(1, getRequestedFor(urlEqualTo("/rss?s=AAPL")));
    }

    @Test void sendsDescriptiveUserAgent() {
        stubXml("/rss?s=AAPL", YAHOO_RSS);
        feed("yahoo-rss", "/rss?s={symbol}", "news", 5000).companyNews("AAPL", FROM, TO);
        wm.verify(getRequestedFor(urlEqualTo("/rss?s=AAPL"))
                .withHeader("User-Agent", equalTo("agora-news/1.0")));
    }

    @Test void sendsPerFeedUserAgentWhenConfigured() {
        stubXml("/rss?s=AAPL", YAHOO_RSS);
        String customUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/138.0.0.0";
        new RssNewsProvider("reddit-stocks", wm.baseUrl() + "/rss?s={symbol}", "social",
                5000, 900L, System::currentTimeMillis, customUa, 0L)
                .companyNews("AAPL", FROM, TO);
        wm.verify(getRequestedFor(urlEqualTo("/rss?s=AAPL"))
                .withHeader("User-Agent", equalTo(customUa)));
    }

    @Test void rateLimitedResponseSetsCooldownFromResetHeaderAndSuppressesFurtherRequests() {
        wm.stubFor(get(urlEqualTo("/rss?s=AAPL")).willReturn(aResponse()
                .withStatus(429).withHeader("x-ratelimit-reset", "30")));
        AtomicLong clock = new AtomicLong(0L);
        RssNewsProvider p = new RssNewsProvider("reddit-stocks", wm.baseUrl() + "/rss?s={symbol}",
                "social", 5000, 900L, clock::get, DEFAULT_UA, 0L);

        // First call: real HTTP round-trip, 429 observed, cooldown recorded.
        assertThatThrownBy(() -> p.companyNews("AAPL", FROM, TO))
                .isInstanceOfSatisfying(MarketDataException.class, e -> {
                    assertThat(e.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE);
                    assertThat(e.getMessage()).isEqualTo("rss:reddit-stocks rate limited");
                });
        wm.verify(1, getRequestedFor(urlEqualTo("/rss?s=AAPL")));

        // Second call still inside the 30s cooldown: no HTTP request at all.
        assertThatThrownBy(() -> p.companyNews("AAPL", FROM, TO))
                .isInstanceOfSatisfying(MarketDataException.class, e ->
                        assertThat(e.getMessage()).isEqualTo("rss:reddit-stocks rate limited (cooldown)"));
        wm.verify(1, getRequestedFor(urlEqualTo("/rss?s=AAPL")));

        // Advance the fake clock past the reset window: request goes out again.
        clock.addAndGet(30_000L);
        assertThatThrownBy(() -> p.companyNews("AAPL", FROM, TO))
                .isInstanceOfSatisfying(MarketDataException.class, e ->
                        assertThat(e.getMessage()).isEqualTo("rss:reddit-stocks rate limited"));
        wm.verify(2, getRequestedFor(urlEqualTo("/rss?s=AAPL")));
    }

    @Test void rateLimitFallsBackToRetryAfterHeaderWhenResetHeaderAbsent() {
        wm.stubFor(get(urlEqualTo("/rss?s=AAPL")).willReturn(aResponse()
                .withStatus(429).withHeader("Retry-After", "15")));
        AtomicLong clock = new AtomicLong(0L);
        RssNewsProvider p = new RssNewsProvider("reddit-stocks", wm.baseUrl() + "/rss?s={symbol}",
                "social", 5000, 900L, clock::get, DEFAULT_UA, 0L);
        assertThatThrownBy(() -> p.companyNews("AAPL", FROM, TO)).isInstanceOf(MarketDataException.class);

        clock.addAndGet(14_000L);
        assertThatThrownBy(() -> p.companyNews("AAPL", FROM, TO))
                .isInstanceOfSatisfying(MarketDataException.class, e ->
                        assertThat(e.getMessage()).isEqualTo("rss:reddit-stocks rate limited (cooldown)"));
        wm.verify(1, getRequestedFor(urlEqualTo("/rss?s=AAPL")));

        clock.addAndGet(1_000L);
        assertThatThrownBy(() -> p.companyNews("AAPL", FROM, TO)).isInstanceOf(MarketDataException.class);
    }

    @Test void rateLimitFallsBackToDefaultSixtySecondsWhenNoHeadersPresent() {
        wm.stubFor(get(urlEqualTo("/rss?s=AAPL")).willReturn(aResponse().withStatus(429)));
        AtomicLong clock = new AtomicLong(0L);
        RssNewsProvider p = new RssNewsProvider("reddit-stocks", wm.baseUrl() + "/rss?s={symbol}",
                "social", 5000, 900L, clock::get, DEFAULT_UA, 0L);
        assertThatThrownBy(() -> p.companyNews("AAPL", FROM, TO)).isInstanceOf(MarketDataException.class);

        clock.addAndGet(59_000L);
        assertThatThrownBy(() -> p.companyNews("AAPL", FROM, TO))
                .isInstanceOfSatisfying(MarketDataException.class, e ->
                        assertThat(e.getMessage()).isEqualTo("rss:reddit-stocks rate limited (cooldown)"));
        wm.verify(1, getRequestedFor(urlEqualTo("/rss?s=AAPL")));

        clock.addAndGet(1_000L);
        assertThatThrownBy(() -> p.companyNews("AAPL", FROM, TO)).isInstanceOf(MarketDataException.class);
        wm.verify(2, getRequestedFor(urlEqualTo("/rss?s=AAPL")));
    }

    @Test void successfulCallSetsCooldownForMinIntervalAndSisterFeedOnSameHostSkips() {
        stubXml("/rss?s=AAPL", YAHOO_RSS);
        AtomicLong clock = new AtomicLong(0L);
        RssNewsProvider primary = new RssNewsProvider("reddit-stocks", wm.baseUrl() + "/rss?s={symbol}",
                "social", 5000, 900L, clock::get, DEFAULT_UA, 61_000L);
        RssNewsProvider sister = new RssNewsProvider("reddit-wsb", wm.baseUrl() + "/rss?s={symbol}",
                "social", 5000, 900L, clock::get, DEFAULT_UA, 0L);

        assertThat(primary.companyNews("AAPL", FROM, TO)).hasSize(3);
        wm.verify(1, getRequestedFor(urlEqualTo("/rss?s=AAPL")));

        // Sister feed shares the same host: skips via cooldown, no second request.
        assertThatThrownBy(() -> sister.companyNews("AAPL", FROM, TO))
                .isInstanceOfSatisfying(MarketDataException.class, e ->
                        assertThat(e.getMessage()).isEqualTo("rss:reddit-wsb rate limited (cooldown)"));
        wm.verify(1, getRequestedFor(urlEqualTo("/rss?s=AAPL")));

        // Past the min-interval, the sister feed's request goes out.
        clock.addAndGet(61_000L);
        assertThat(sister.companyNews("AAPL", FROM, TO)).hasSize(3);
        wm.verify(2, getRequestedFor(urlEqualTo("/rss?s=AAPL")));
    }

    @Test void cooldownOnOneHostDoesNotAffectADifferentHost() {
        stubXml("/rss?s=AAPL", YAHOO_RSS);
        AtomicLong clock = new AtomicLong(0L);
        RssNewsProvider primary = new RssNewsProvider("reddit-stocks", wm.baseUrl() + "/rss?s={symbol}",
                "social", 5000, 900L, clock::get, DEFAULT_UA, 61_000L);
        String otherHostUrl = wm.baseUrl().replace("localhost", "127.0.0.1") + "/rss?s={symbol}";
        RssNewsProvider otherHost = new RssNewsProvider("other-host", otherHostUrl,
                "news", 5000, 900L, clock::get, DEFAULT_UA, 0L);

        assertThat(primary.companyNews("AAPL", FROM, TO)).hasSize(3);
        // Different host string ("127.0.0.1" vs "localhost") is unaffected by primary's cooldown.
        assertThat(otherHost.companyNews("AAPL", FROM, TO)).hasSize(3);
    }
}
