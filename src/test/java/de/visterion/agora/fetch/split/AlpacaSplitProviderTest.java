package de.visterion.agora.fetch.split;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.alpaca.AlpacaDataClient;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class AlpacaSplitProviderTest {
    static WireMockServer wm;
    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    private AlpacaSplitProvider provider(boolean configured) {
        return new AlpacaSplitProvider(new AlpacaDataClient(RestClient.builder().baseUrl(wm.baseUrl()).build(), configured));
    }

    @Test void parsesForwardSplit() {
        wm.stubFor(get(urlPathEqualTo("/v1beta1/corporate-actions"))
            .withQueryParam("symbols", equalTo("NVDA"))
            .willReturn(okJson("{\"corporate_actions\":{\"forward_splits\":[{\"ex_date\":\"2024-06-10\",\"new_rate\":10,\"old_rate\":1,\"symbol\":\"NVDA\"}]}}")));
        List<SplitEvent> s = provider(true).splits("NVDA");
        assertThat(s).hasSize(1);
        assertThat(s.get(0).date().toString()).isEqualTo("2024-06-10");
        assertThat(s.get(0).fromFactor()).isEqualByComparingTo("1");
        assertThat(s.get(0).toFactor()).isEqualByComparingTo("10");
    }

    @Test void parsesReverseSplit() {
        wm.stubFor(get(urlPathEqualTo("/v1beta1/corporate-actions"))
            .willReturn(okJson("{\"corporate_actions\":{\"reverse_splits\":[{\"ex_date\":\"2022-05-01\",\"new_rate\":1,\"old_rate\":8,\"symbol\":\"X\"}]}}")));
        List<SplitEvent> s = provider(true).splits("X");
        assertThat(s).hasSize(1);
        assertThat(s.get(0).fromFactor()).isEqualByComparingTo("8");
        assertThat(s.get(0).toFactor()).isEqualByComparingTo("1");
    }

    @Test void emptyCorporateActions_returnsEmpty() {
        wm.stubFor(get(urlPathEqualTo("/v1beta1/corporate-actions"))
            .willReturn(okJson("{\"corporate_actions\":{}}")));
        assertThat(provider(true).splits("NVDA")).isEmpty();
    }

    @Test void followsNextPageToken_mergesAllPages() {
        wm.stubFor(get(urlPathEqualTo("/v1beta1/corporate-actions"))
            .withQueryParam("symbols", equalTo("NVDA"))
            .withQueryParam("page_token", com.github.tomakehurst.wiremock.client.WireMock.absent())
            .willReturn(okJson("""
                {"corporate_actions":{"forward_splits":[
                  {"ex_date":"2000-06-27","new_rate":2,"old_rate":1,"symbol":"NVDA"}
                ]},"next_page_token":"abc123"}
                """)));
        wm.stubFor(get(urlPathEqualTo("/v1beta1/corporate-actions"))
            .withQueryParam("symbols", equalTo("NVDA"))
            .withQueryParam("page_token", equalTo("abc123"))
            .willReturn(okJson("""
                {"corporate_actions":{"forward_splits":[
                  {"ex_date":"2024-06-10","new_rate":10,"old_rate":1,"symbol":"NVDA"}
                ]}}
                """)));
        List<SplitEvent> s = provider(true).splits("NVDA");
        assertThat(s).hasSize(2);
        assertThat(s).extracting(e -> e.date().toString()).containsExactlyInAnyOrder("2000-06-27", "2024-06-10");
    }

    @Test void notConfigured_throws() {
        assertThatThrownBy(() -> provider(false).splits("NVDA")).isInstanceOf(MarketDataException.class);
    }

    @Test void name_isAlpaca() { assertThat(provider(true).name()).isEqualTo("alpaca"); }
}
