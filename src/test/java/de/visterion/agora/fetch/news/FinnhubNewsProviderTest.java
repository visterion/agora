package de.visterion.agora.fetch.news;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.finnhub.FinnhubClient;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class FinnhubNewsProviderTest {
    static WireMockServer wm;
    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    private FinnhubNewsProvider svc(String key) {
        FinnhubClient client = new FinnhubClient(RestClient.builder().baseUrl(wm.baseUrl()).build(), key);
        return new FinnhubNewsProvider(client, 900L, System::currentTimeMillis);
    }

    @Test void parsesHeadlines() {
        wm.stubFor(get(urlPathEqualTo("/company-news"))
                .withQueryParam("symbol", equalTo("AAPL"))
                .withHeader("X-Finnhub-Token", equalTo("k"))
                .willReturn(okJson("""
                    [{"headline":"Apple beats","summary":"strong quarter","source":"Reuters","datetime":1749600000,"url":"http://x/1"},
                     {"headline":"","summary":"skip me","source":"X","datetime":1749600001,"url":"http://x/2"}]
                    """)));
        List<NewsItem> news = svc("k").companyNews("AAPL", LocalDate.parse("2025-01-01"), LocalDate.parse("2025-01-08"));
        assertThat(news).hasSize(1);
        assertThat(news.get(0).headline()).isEqualTo("Apple beats");
        assertThat(news.get(0).source()).isEqualTo("Reuters");
    }

    @Test void tagsEveryItemAsSourceTypeNews() {
        wm.stubFor(get(urlPathEqualTo("/company-news"))
                .willReturn(okJson("[{\"headline\":\"h\",\"datetime\":1,\"url\":\"u\",\"source\":\"s\",\"summary\":\"x\"}]")));
        List<NewsItem> news = svc("k").companyNews("AAPL", LocalDate.parse("2025-01-01"), LocalDate.parse("2025-01-08"));
        assertThat(news.get(0).sourceType()).isEqualTo("news");
    }

    @Test void blankKeyThrowsUnavailable() {
        assertThatThrownBy(() -> svc("").companyNews("AAPL", LocalDate.parse("2025-01-01"), LocalDate.parse("2025-01-08")))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE));
    }

    @Test void httpErrorThrowsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/company-news")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> svc("k").companyNews("AAPL", LocalDate.parse("2025-01-01"), LocalDate.parse("2025-01-08")))
                .isInstanceOf(MarketDataException.class);
    }

    @Test void missingDatetimeYieldsNullNotEpochZero() {
        wm.stubFor(get(urlPathEqualTo("/company-news"))
                .willReturn(okJson("""
                    [{"headline":"Apple beats","summary":"s","source":"Reuters","url":"http://x/1"}]
                    """)));
        List<NewsItem> news = svc("k").companyNews("AAPL", LocalDate.parse("2025-01-01"), LocalDate.parse("2025-01-08"));
        assertThat(news).hasSize(1);
        assertThat(news.get(0).datetime()).isNull();
    }

    /** Recorded 2026-07-18 via live company-news call (proxied through coordinator; key never
     *  stored). Finding: ALL urls in the real response (247/247 items, AAPL, 2026-07-10..16)
     *  are finnhub.io proxy links of the form {@code https://finnhub.io/api/news?id=<64-hex>} —
     *  zero publisher-direct hosts. {@code source} carries the display name instead (observed:
     *  Yahoo, Benzinga, CNBC, ChartMill, SeekingAlpha). Pins the URL SHAPE: proxied-only hosts,
     *  so the consumer's seed table needs display-name source rows (T1.4, R3 Major 2). */
    @Test void recordedRealCompanyNewsUrlsAreFinnhubProxied() {
        wm.stubFor(get(urlPathEqualTo("/company-news"))
                .willReturn(okJson("""
                    [{"headline":"SpaceX Just Fell Below Its IPO Price. Here's What Happens Next, According to History.","summary":"Investors have closely followed SpaceX since its record IPO.","source":"Yahoo","datetime":1784244600,"url":"https://finnhub.io/api/news?id=30b15c39c282ba5bc7ceac3e951a2ade41140cc9fa08af9d8cae4cfdcebaaac9"},
                     {"headline":"Korea Drives Semi Stocks Lower Despite Solid Taiwan Semi Earnings; Apple Boosts China AI Models","summary":"Korea Leading Semiconductors","source":"Benzinga","datetime":1784211511,"url":"https://finnhub.io/api/news?id=c44a54e68a11aa7e1cc92edf0a125464642478926263eb1b4a8dffac95f11a71"}]
                    """)));
        List<NewsItem> news = svc("k").companyNews("AAPL", LocalDate.parse("2026-07-11"), LocalDate.parse("2026-07-18"));
        assertThat(news).hasSize(2);
        assertThat(java.net.URI.create(news.get(0).url()).getHost()).isEqualTo("finnhub.io");
        assertThat(java.net.URI.create(news.get(1).url()).getHost()).isEqualTo("finnhub.io");
        assertThat(news.get(0).source()).isEqualTo("Yahoo");
        assertThat(news.get(1).source()).isEqualTo("Benzinga");
    }

    @Test void cachesSuccess() {
        wm.stubFor(get(urlPathEqualTo("/company-news"))
                .willReturn(okJson("[{\"headline\":\"h\",\"datetime\":1,\"url\":\"u\",\"source\":\"s\",\"summary\":\"x\"}]")));
        var s = svc("k");
        s.companyNews("AAPL", LocalDate.parse("2025-01-01"), LocalDate.parse("2025-01-08"));
        s.companyNews("AAPL", LocalDate.parse("2025-01-01"), LocalDate.parse("2025-01-08"));
        wm.verify(1, getRequestedFor(urlPathEqualTo("/company-news")));
    }

    @Test void providerLeavesDomainNullOnlyTheAggregatorSetsIt() {
        wm.stubFor(get(urlPathEqualTo("/company-news"))
                .willReturn(okJson("[{\"headline\":\"h\",\"datetime\":1,\"url\":\"https://www.reuters.com/a\",\"source\":\"s\",\"summary\":\"x\"}]")));
        List<NewsItem> news = svc("k").companyNews("AAPL", LocalDate.parse("2025-01-01"), LocalDate.parse("2025-01-08"));
        assertThat(news.get(0).domain()).isNull();
    }

    @Test void nonUsSymbolSkipsFinnhubAndReturnsEmpty() {
        List<NewsItem> news = svc("k").companyNews("SAP.DE", LocalDate.parse("2025-01-01"), LocalDate.parse("2025-01-08"));
        assertThat(news).isEmpty();
        wm.verify(0, getRequestedFor(urlPathEqualTo("/company-news")));
    }

    @Test void usSymbolStillCallsFinnhub() {
        wm.stubFor(get(urlPathEqualTo("/company-news"))
                .withQueryParam("symbol", equalTo("AAPL"))
                .willReturn(okJson("[{\"headline\":\"h\",\"datetime\":1,\"url\":\"u\",\"source\":\"s\",\"summary\":\"x\"}]")));
        List<NewsItem> news = svc("k").companyNews("AAPL", LocalDate.parse("2025-01-01"), LocalDate.parse("2025-01-08"));
        assertThat(news).hasSize(1);
        wm.verify(1, getRequestedFor(urlPathEqualTo("/company-news")));
    }
}
