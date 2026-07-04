package de.visterion.agora.data;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class FxServiceTest {
    static WireMockServer wm;
    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    private FxService svc() { return new FxService(wm.baseUrl(), "TestAgent/1.0", 120L, System::currentTimeMillis); }

    @Test void identityWhenSameCurrency() {
        FxRate r = svc().rate("USD", "USD");
        assertThat(r.rate()).isEqualByComparingTo("1");
    }

    @Test void fetchesPairPrice() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/EURUSD=X"))
                .willReturn(okJson("""
                    {"chart":{"result":[{"meta":{"regularMarketPrice":1.0842}}],"error":null}}
                    """)));
        FxRate r = svc().rate("EUR", "USD");
        assertThat(r.from()).isEqualTo("EUR");
        assertThat(r.to()).isEqualTo("USD");
        assertThat(r.rate()).isEqualByComparingTo("1.0842");
    }

    @Test void invalidCurrencyThrowsUnavailable() {
        assertThatThrownBy(() -> svc().rate("EU R", "USD"))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE));
        assertThatThrownBy(() -> svc().rate("EURO", "USD"))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE));
    }

    @Test void missingPriceThrowsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/EURGBP=X"))
                .willReturn(okJson("{\"chart\":{\"result\":[{\"meta\":{}}],\"error\":null}}")));
        assertThatThrownBy(() -> svc().rate("EUR", "GBP"))
                .isInstanceOf(MarketDataException.class);
    }

    @Test void serverErrorThrowsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/EURUSD=X")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> svc().rate("EUR", "USD"))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE));
    }

    @Test void cachesSuccess() {
        wm.stubFor(get(urlPathEqualTo("/v8/finance/chart/EURUSD=X"))
                .willReturn(okJson("{\"chart\":{\"result\":[{\"meta\":{\"regularMarketPrice\":1.08}}],\"error\":null}}")));
        var s = svc();
        s.rate("EUR", "USD");
        s.rate("EUR", "USD");
        wm.verify(1, getRequestedFor(urlPathEqualTo("/v8/finance/chart/EURUSD=X")));
    }
}
