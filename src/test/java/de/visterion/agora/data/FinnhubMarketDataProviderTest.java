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
                .withHeader("X-Finnhub-Token", equalTo("k"))
                .willReturn(okJson("""
                    {"c":190.5,"d":2.4,"dp":1.27,"h":191.0,"l":188.0,"o":188.5,"pc":188.1}
                    """)));
        Quote q = withKey("k").quote("AAPL");
        assertThat(q.price()).isEqualByComparingTo("190.5");
        assertThat(q.dayChangePercent()).isEqualByComparingTo("1.27");
        assertThat(q.currency()).isEqualTo("USD");
    }

    // H8: the key must ride as a header, never as a `token=` query param (query params end up
    // embedded in ResourceAccessException messages on I/O failure).
    @Test void keyNeverSentAsQueryParam() {
        wm.stubFor(get(urlPathEqualTo("/quote"))
                .withHeader("X-Finnhub-Token", equalTo("supersecret"))
                .willReturn(okJson("""
                    {"c":190.5,"d":2.4,"dp":1.27}
                    """)));
        withKey("supersecret").quote("AAPL");
        wm.verify(getRequestedFor(urlPathEqualTo("/quote")).withoutQueryParam("token"));
    }

    // H8b: a transport-layer failure (e.g. a ResourceAccessException wrapping a timeout) must not
    // leak the request URL or the API key into the client-facing MarketDataException message.
    @Test void transportFailureMessageDoesNotLeakUrlOrKey() {
        wm.stubFor(get(urlPathEqualTo("/quote")).willReturn(okJson("{}").withFixedDelay(2_000)));
        var fast = new FinnhubMarketDataProvider(wm.baseUrl(), "supersecretkey123", 200L);
        assertThatThrownBy(() -> fast.quote("AAPL"))
                .isInstanceOfSatisfying(MarketDataException.class, e -> {
                    assertThat(e.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE);
                    assertThat(e.getMessage()).doesNotContain("supersecretkey123");
                    assertThat(e.getMessage().toLowerCase()).doesNotContain("http");
                });
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
