package de.visterion.agora.fetch.finnhub;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class SplitServiceTest {
    static WireMockServer wm;
    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    private SplitService svc(String key) {
        return new SplitService(new FinnhubClient(RestClient.builder().baseUrl(wm.baseUrl()).build(), key),
            21600L, System::currentTimeMillis);
    }

    @Test void parsesSplits() {
        wm.stubFor(get(urlPathEqualTo("/stock/split"))
            .withQueryParam("symbol", equalTo("NVDA"))
            .willReturn(okJson("""
                [{"symbol":"NVDA","date":"2024-06-10","fromFactor":1,"toFactor":10}]
                """)));
        List<SplitEvent> s = svc("k").splits("NVDA");
        assertThat(s).hasSize(1);
        assertThat(s.get(0).date().toString()).isEqualTo("2024-06-10");
        assertThat(s.get(0).toFactor()).isEqualByComparingTo("10");
    }

    @Test void emptyArray_returnsEmpty() {
        wm.stubFor(get(urlPathEqualTo("/stock/split")).willReturn(okJson("[]")));
        assertThat(svc("k").splits("NVDA")).isEmpty();
    }
}
