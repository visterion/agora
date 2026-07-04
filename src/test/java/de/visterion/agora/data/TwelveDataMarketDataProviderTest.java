package de.visterion.agora.data;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class TwelveDataMarketDataProviderTest {
    static WireMockServer wm;
    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    private TwelveDataMarketDataProvider withKey(String key) {
        return new TwelveDataMarketDataProvider(wm.baseUrl(), key);
    }

    @Test void nameIsTwelvedata() {
        assertThat(withKey("k").name()).isEqualTo("twelvedata");
    }

    @Test void quoteParsesCloseAndPercentChange() {
        wm.stubFor(get(urlPathEqualTo("/quote"))
                .withQueryParam("symbol", equalTo("AAPL"))
                .withQueryParam("apikey", equalTo("k"))
                .willReturn(okJson("""
                    {"symbol":"AAPL","name":"Apple Inc","close":"190.5","percent_change":"1.25","currency":"USD"}
                    """)));
        Quote q = withKey("k").quote("AAPL");
        assertThat(q.symbol()).isEqualTo("AAPL");
        assertThat(q.price()).isEqualByComparingTo("190.5");
        assertThat(q.dayChangePercent()).isEqualByComparingTo("1.25");
    }

    @Test void quoteErrorStatusThrowsNotFound() {
        wm.stubFor(get(urlPathEqualTo("/quote")).willReturn(okJson("""
            {"code":404,"message":"symbol not found","status":"error"}
            """)));
        assertThatThrownBy(() -> withKey("k").quote("ZZZZ"))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.NOT_FOUND));
    }

    @Test void quote500ThrowsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/quote")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> withKey("k").quote("AAPL"))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE));
    }

    @Test void blankKeyThrowsUnavailable_noHttp() {
        // no stub → if it hit the network the test would fail differently; blank key must short-circuit
        assertThatThrownBy(() -> withKey("").quote("AAPL"))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE));
    }

    @Test void quoteEmptyBodyThrowsUnavailable() {
        // 200 OK with no body → RestClient yields a null node (or throws); either way must be UNAVAILABLE
        wm.stubFor(get(urlPathEqualTo("/quote"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")));
        assertThatThrownBy(() -> withKey("k").quote("AAPL"))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE));
    }

    @Test void ohlcParsesNewestFirstReversedToOldestFirst() {
        wm.stubFor(get(urlPathEqualTo("/time_series"))
                .withQueryParam("symbol", equalTo("AAPL"))
                .withQueryParam("interval", equalTo("1day"))
                .withQueryParam("apikey", equalTo("k"))
                .willReturn(okJson("""
                    {"values":[
                      {"datetime":"2025-01-03","open":"11.0","high":"11.5","low":"10.8","close":"11.2","volume":"3000"},
                      {"datetime":"2025-01-02","open":"10.0","high":"11.0","low":"9.5","close":"10.5","volume":"1000"}
                    ],"status":"ok"}
                    """)));
        var bars = withKey("k").ohlc("AAPL", 30);
        assertThat(bars).hasSize(2);
        assertThat(bars.get(0).date()).isBefore(bars.get(1).date());
        assertThat(bars.get(0).close()).isEqualByComparingTo("10.5");
        assertThat(bars.get(1).close()).isEqualByComparingTo("11.2");
    }
}
