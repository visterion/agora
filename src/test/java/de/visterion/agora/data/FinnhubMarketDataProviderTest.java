package de.visterion.agora.data;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class FinnhubMarketDataProviderTest {
    static WireMockServer wm;
    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    private FinnhubMarketDataProvider withKey(String key) {
        return new FinnhubMarketDataProvider(wm.baseUrl(), key);
    }

    @Test void nameIsFinnhub() { assertThat(withKey("k").name()).isEqualTo("finnhub"); }

    @Test void quoteParsesCurrentAndPercent() {
        wm.stubFor(get(urlPathEqualTo("/quote"))
                .withQueryParam("symbol", equalTo("AAPL"))
                .withQueryParam("token", equalTo("k"))
                .willReturn(okJson("""
                    {"c":190.5,"d":2.4,"dp":1.27,"h":191.0,"l":188.0,"o":188.5,"pc":188.1}
                    """)));
        Quote q = withKey("k").quote("AAPL");
        assertThat(q.price()).isEqualByComparingTo("190.5");
        assertThat(q.dayChangePercent()).isEqualByComparingTo("1.27");
        assertThat(q.currency()).isEqualTo("USD");
    }

    @Test void zeroCloseThrowsNotFound() {
        wm.stubFor(get(urlPathEqualTo("/quote")).willReturn(okJson("""
            {"c":0,"d":0,"dp":0,"pc":0}
            """)));
        assertThatThrownBy(() -> withKey("k").quote("ZZZZ"))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.NOT_FOUND));
    }

    @Test void blankKeyThrowsUnavailable() {
        assertThatThrownBy(() -> withKey("").quote("AAPL"))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE));
    }

    @Test void ohlcAlwaysUnavailable() {
        assertThatThrownBy(() -> withKey("k").ohlc("AAPL", 30))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE));
    }
}
