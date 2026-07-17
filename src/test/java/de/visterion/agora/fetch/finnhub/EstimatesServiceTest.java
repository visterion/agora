package de.visterion.agora.fetch.finnhub;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.agora.data.MarketDataException;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class EstimatesServiceTest {
    static WireMockServer wm;
    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    private EstimatesService svc(String key) {
        return new EstimatesService(new FinnhubClient(RestClient.builder().baseUrl(wm.baseUrl()).build(), key),
                21600L, System::currentTimeMillis);
    }

    @Test void parsesRecommendations() {
        wm.stubFor(get(urlPathEqualTo("/stock/recommendation"))
                .withQueryParam("symbol", equalTo("AAPL"))
                .withHeader("X-Finnhub-Token", equalTo("k"))
                .willReturn(okJson("""
                    [{"period":"2025-06-01","strongBuy":10,"buy":15,"hold":5,"sell":1,"strongSell":0}]
                    """)));
        List<Recommendation> recs = svc("k").recommendations("AAPL");
        assertThat(recs).hasSize(1);
        assertThat(recs.get(0).strongBuy()).isEqualTo(10);
        assertThat(recs.get(0).period()).isEqualTo("2025-06-01");
    }

    @Test void blankKeyThrowsUnavailable() {
        assertThatThrownBy(() -> svc("").recommendations("AAPL")).isInstanceOf(MarketDataException.class);
    }

    @Test void httpErrorThrowsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/stock/recommendation")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> svc("k").recommendations("AAPL")).isInstanceOf(MarketDataException.class);
    }

    @Test void nonUsSymbolSkipsFinnhubAndReturnsEmpty() {
        List<Recommendation> recs = svc("k").recommendations("SAP.DE");
        assertThat(recs).isEmpty();
        wm.verify(0, getRequestedFor(urlPathEqualTo("/stock/recommendation")));
    }

    @Test void usSymbolStillCallsFinnhub() {
        wm.stubFor(get(urlPathEqualTo("/stock/recommendation"))
                .withQueryParam("symbol", equalTo("AAPL"))
                .willReturn(okJson("""
                    [{"period":"2025-06-01","strongBuy":10,"buy":15,"hold":5,"sell":1,"strongSell":0}]
                    """)));
        List<Recommendation> recs = svc("k").recommendations("AAPL");
        assertThat(recs).hasSize(1);
        wm.verify(1, getRequestedFor(urlPathEqualTo("/stock/recommendation")));
    }
}
