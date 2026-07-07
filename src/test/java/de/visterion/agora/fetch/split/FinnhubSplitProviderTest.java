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

    private FinnhubSplitProvider provider(String key) {
        return new FinnhubSplitProvider(new FinnhubClient(RestClient.builder().baseUrl(wm.baseUrl()).build(), key));
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

    @Test void blankKeyThrows() {
        assertThatThrownBy(() -> provider("").splits("NVDA")).isInstanceOf(MarketDataException.class);
    }

    @Test void name_isFinnhub() { assertThat(provider("k").name()).isEqualTo("finnhub"); }
}
