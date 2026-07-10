package de.visterion.agora.fetch.finnhub;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.agora.data.MarketDataException;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class FundamentalsServiceTest {
    static WireMockServer wm;
    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    private FundamentalsService svc(String key) {
        return new FundamentalsService(new FinnhubClient(RestClient.builder().baseUrl(wm.baseUrl()).build(), key),
                21600L, System::currentTimeMillis);
    }

    @Test void parsesMetricObject() {
        wm.stubFor(get(urlPathEqualTo("/stock/metric"))
                .withQueryParam("symbol", equalTo("AAPL"))
                .withQueryParam("metric", equalTo("all"))
                .withHeader("X-Finnhub-Token", equalTo("k"))
                .willReturn(okJson("""
                    {"metric":{"peTTM":28.5,"52WeekHigh":199.6,"roaTTM":0.28},"metricType":"all","symbol":"AAPL"}
                    """)));
        Fundamentals f = svc("k").fundamentals("AAPL");
        assertThat(f.symbol()).isEqualTo("AAPL");
        assertThat(f.metrics().get("peTTM").asDouble()).isEqualTo(28.5);
    }

    @Test void blankKeyThrowsUnavailable() {
        assertThatThrownBy(() -> svc("").fundamentals("AAPL")).isInstanceOf(MarketDataException.class);
    }

    @Test void missingMetricThrowsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/stock/metric")).willReturn(okJson("{\"symbol\":\"AAPL\"}")));
        assertThatThrownBy(() -> svc("k").fundamentals("AAPL")).isInstanceOf(MarketDataException.class);
    }
}
