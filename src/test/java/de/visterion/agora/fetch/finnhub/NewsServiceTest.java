package de.visterion.agora.fetch.finnhub;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.agora.data.MarketDataException;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class NewsServiceTest {
    static WireMockServer wm;
    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    private NewsService svc(String key) {
        FinnhubClient client = new FinnhubClient(RestClient.builder().baseUrl(wm.baseUrl()).build(), key);
        return new NewsService(client, 900L, System::currentTimeMillis);
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

    @Test void cachesSuccess() {
        wm.stubFor(get(urlPathEqualTo("/company-news"))
                .willReturn(okJson("[{\"headline\":\"h\",\"datetime\":1,\"url\":\"u\",\"source\":\"s\",\"summary\":\"x\"}]")));
        var s = svc("k");
        s.companyNews("AAPL", LocalDate.parse("2025-01-01"), LocalDate.parse("2025-01-08"));
        s.companyNews("AAPL", LocalDate.parse("2025-01-01"), LocalDate.parse("2025-01-08"));
        wm.verify(1, getRequestedFor(urlPathEqualTo("/company-news")));
    }
}
