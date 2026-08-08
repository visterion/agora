package de.visterion.agora.fetch.split;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.finnhub.FinnhubClient;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class FinnhubSplitProviderTest {
    static WireMockServer wm;
    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    // These pre-existing tests exercise the HTTP path directly, so they pass splitsEnabled=true
    // explicitly — the one-arg constructor was removed because it silently defaulted to true,
    // the opposite of the production default (agora.data.finnhub.splits-enabled defaults false).
    private FinnhubSplitProvider provider(String key) {
        return new FinnhubSplitProvider(new FinnhubClient(RestClient.builder().baseUrl(wm.baseUrl()).build(), key), true);
    }

    @Test void parsesSplits() {
        wm.stubFor(get(urlPathEqualTo("/stock/split"))
            .withQueryParam("symbol", equalTo("NVDA"))
            .willReturn(okJson("[{\"symbol\":\"NVDA\",\"date\":\"2024-06-10\",\"fromFactor\":1,\"toFactor\":10}]")));
        List<SplitEvent> s = provider("k").splits("NVDA");
        assertThat(s).hasSize(1);
        assertThat(s.get(0).date().toString()).isEqualTo("2024-06-10");
        assertThat(s.get(0).toFactor()).isEqualByComparingTo("10");
    }

    @Test void emptyArray_returnsEmpty() {
        wm.stubFor(get(urlPathEqualTo("/stock/split")).willReturn(okJson("[]")));
        assertThat(provider("k").splits("NVDA")).isEmpty();
    }

    @Test void malformedEntry_isSkipped() {
        wm.stubFor(get(urlPathEqualTo("/stock/split")).willReturn(okJson(
            "[{\"date\":\"not-a-date\",\"fromFactor\":1,\"toFactor\":10},{\"date\":\"2024-06-10\",\"fromFactor\":1,\"toFactor\":10}]")));
        assertThat(provider("k").splits("NVDA")).hasSize(1);
    }

    @Test void keyNeverSentAsQueryParam() {
        wm.stubFor(get(urlPathEqualTo("/stock/split"))
            .withHeader("X-Finnhub-Token", equalTo("supersecret"))
            .willReturn(okJson("[]")));
        provider("supersecret").splits("NVDA");
        wm.verify(getRequestedFor(urlPathEqualTo("/stock/split")).withoutQueryParam("token"));
    }

    @Test void blankKeyThrows() {
        assertThatThrownBy(() -> provider("").splits("NVDA")).isInstanceOf(MarketDataException.class);
    }

    @Test void name_isFinnhub() { assertThat(provider("k").name()).isEqualTo("finnhub"); }

    @Test void splitsDisabled_throwsWithoutCallingFinnhub() {
        // agora.data.finnhub.splits-enabled=false (the production default since 2026-08-08): the
        // provider must report itself unavailable without making the HTTP call that would 403.
        FinnhubSplitProvider p = new FinnhubSplitProvider(
                new FinnhubClient(RestClient.builder().baseUrl(wm.baseUrl()).build(), "k"), false);
        assertThatThrownBy(() -> p.splits("VICI")).isInstanceOf(MarketDataException.class);
        wm.verify(0, getRequestedFor(urlPathEqualTo("/stock/split")));
    }

    @Test void splitsEnabled_behavesLikeToday() {
        wm.stubFor(get(urlPathEqualTo("/stock/split"))
            .withQueryParam("symbol", equalTo("NVDA"))
            .willReturn(okJson("[{\"symbol\":\"NVDA\",\"date\":\"2024-06-10\",\"fromFactor\":1,\"toFactor\":10}]")));
        FinnhubSplitProvider p = new FinnhubSplitProvider(
                new FinnhubClient(RestClient.builder().baseUrl(wm.baseUrl()).build(), "k"), true);
        assertThat(p.splits("NVDA")).hasSize(1);
        wm.verify(1, getRequestedFor(urlPathEqualTo("/stock/split")));
    }
}
