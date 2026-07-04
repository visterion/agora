package de.visterion.agora.fetch.edgar;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class EdgarCikResolverTest {
    static WireMockServer wm;
    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    private EdgarCikResolver resolver() {
        return new EdgarCikResolver(RestClient.builder().baseUrl(wm.baseUrl()).build());
    }

    @Test void resolvesAndZeroPads() {
        wm.stubFor(get(urlPathEqualTo("/files/company_tickers.json"))
                .willReturn(okJson("""
                    {"0":{"cik_str":320193,"ticker":"AAPL","title":"Apple Inc"},
                     "1":{"cik_str":789019,"ticker":"MSFT","title":"Microsoft"}}
                    """)));
        assertThat(resolver().cik("aapl")).contains("0000320193");
    }

    @Test void unknownTickerEmpty() {
        wm.stubFor(get(urlPathEqualTo("/files/company_tickers.json"))
                .willReturn(okJson("{\"0\":{\"cik_str\":320193,\"ticker\":\"AAPL\"}}")));
        assertThat(resolver().cik("ZZZZ")).isEmpty();
    }

    @Test void blankTickerEmpty() {
        assertThat(resolver().cik("  ")).isEmpty();
    }
}
