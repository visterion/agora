package de.visterion.agora.fetch.finnhub;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.agora.data.MarketDataException;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class EarningsEstimatesServiceTest {
    static WireMockServer wm;
    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    private EarningsEstimatesService svc(String key) {
        return new EarningsEstimatesService(
            new FinnhubClient(RestClient.builder().baseUrl(wm.baseUrl()).build(), key),
            21600L, System::currentTimeMillis);
    }

    @Test void parsesEarnings() {
        wm.stubFor(get(urlPathEqualTo("/stock/earnings"))
            .withQueryParam("symbol", equalTo("AAPL"))
            .withHeader("X-Finnhub-Token", equalTo("k"))
            .willReturn(okJson("""
                [{"period":"2026-03-31","actual":1.5,"estimate":1.4,"surprise":0.1,"surprisePercent":7.14},
                 {"period":"2025-12-31","actual":2.1,"estimate":2.2,"surprise":-0.1,"surprisePercent":-4.5}]
                """)));
        List<EarningsEstimate> e = svc("k").earnings("AAPL");
        assertThat(e).hasSize(2);
        assertThat(e.get(0).period()).isEqualTo("2026-03-31");
        assertThat(e.get(0).surprise()).isEqualByComparingTo("0.1");
        assertThat(e.get(1).actual()).isEqualByComparingTo("2.1");
    }

    @Test void missingNumericsYieldNullNotZero() {
        wm.stubFor(get(urlPathEqualTo("/stock/earnings"))
            .willReturn(okJson("[{\"period\":\"2026-03-31\"}]")));
        List<EarningsEstimate> e = svc("k").earnings("AAPL");
        assertThat(e).hasSize(1);
        assertThat(e.get(0).actual()).isNull();
        assertThat(e.get(0).estimate()).isNull();
        assertThat(e.get(0).surprise()).isNull();
        assertThat(e.get(0).surprisePercent()).isNull();
    }

    @Test void blankKeyThrows() {
        assertThatThrownBy(() -> svc("").earnings("AAPL")).isInstanceOf(MarketDataException.class);
    }

    @Test void nonArrayThrows() {
        wm.stubFor(get(urlPathEqualTo("/stock/earnings")).willReturn(okJson("{}")));
        assertThatThrownBy(() -> svc("k").earnings("AAPL")).isInstanceOf(MarketDataException.class);
    }
}
