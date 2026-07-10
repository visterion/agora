package de.visterion.agora.data;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class IntradayServiceTest {
    static WireMockServer wm;
    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    private IntradayService svc() {
        return new IntradayService(wm.baseUrl(), "TestAgent/1.0", "5m", "1d", 120L, 4_000L, System::currentTimeMillis);
    }

    @Test void parsesBarsSkippingNullCloses() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/AAPL"))
                .withQueryParam("range", equalTo("1d"))
                .withQueryParam("interval", equalTo("5m"))
                .willReturn(okJson("""
                    {"chart":{"result":[{
                      "timestamp":[1749600000,1749600300,1749600600],
                      "indicators":{"quote":[{
                        "open":[10.0,null,10.4],"high":[10.2,null,10.6],
                        "low":[9.9,null,10.3],"close":[10.1,null,10.5],"volume":[100,null,300]
                      }]}
                    }],"error":null}}
                    """)));
        var bars = svc().intraday("AAPL", null, null);
        assertThat(bars).hasSize(2);
        assertThat(bars.get(0).close()).isEqualByComparingTo("10.1");
        assertThat(bars.get(1).close()).isEqualByComparingTo("10.5");
        assertThat(bars.get(0).volume()).isEqualTo(100L);
    }

    @Test void argOverridesInterval() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/AAPL"))
                .withQueryParam("interval", equalTo("15m"))
                .withQueryParam("range", equalTo("5d"))
                .willReturn(okJson("""
                    {"chart":{"result":[{"timestamp":[1749600000],
                      "indicators":{"quote":[{"open":[1.0],"high":[2.0],"low":[0.5],"close":[1.5],"volume":[10]}]}}],"error":null}}
                    """)));
        var bars = svc().intraday("AAPL", "15m", "5d");
        assertThat(bars).hasSize(1);
    }

    @Test void emptyResultThrowsNotFound() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/ZZZZ"))
                .willReturn(okJson("{\"chart\":{\"result\":null,\"error\":{\"code\":\"Not Found\"}}}")));
        assertThatThrownBy(() -> svc().intraday("ZZZZ", null, null))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.NOT_FOUND));
    }

    @Test void serverErrorThrowsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/AAPL")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> svc().intraday("AAPL", null, null))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE));
    }

    @Test void cachesSuccess() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/AAPL"))
                .willReturn(okJson("""
                    {"chart":{"result":[{"timestamp":[1749600000],
                      "indicators":{"quote":[{"open":[1.0],"high":[2.0],"low":[0.5],"close":[1.5],"volume":[10]}]}}],"error":null}}
                    """)));
        var s = svc();
        s.intraday("AAPL", null, null);
        s.intraday("AAPL", null, null);
        wm.verify(1, getRequestedFor(urlPathEqualTo("/v8/finance/chart/AAPL")));
    }

    @Test void emptyBarsThrowNotFoundInsteadOfCachingEmptySuccess() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/AAPL"))
                .willReturn(okJson("""
                    {"chart":{"result":[{"timestamp":[],
                      "indicators":{"quote":[{"open":[],"high":[],"low":[],"close":[],"volume":[]}]}}],"error":null}}
                    """)));
        assertThatThrownBy(() -> svc().intraday("AAPL", null, null))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.NOT_FOUND));
    }
}
