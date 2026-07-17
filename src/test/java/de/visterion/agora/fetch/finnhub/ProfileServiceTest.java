package de.visterion.agora.fetch.finnhub;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.agora.data.MarketDataException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class ProfileServiceTest {

    private WireMockServer wm;

    @BeforeEach void start() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
    }

    @AfterEach void stop() { wm.stop(); }

    private ProfileService service(String key) {
        RestClient http = RestClient.builder().baseUrl(wm.baseUrl()).build();
        FinnhubClient client = new FinnhubClient(http, key);
        return new ProfileService(client, 120L, System::currentTimeMillis);
    }

    @Test void returnsRawProfileObject() {
        wm.stubFor(get(urlPathEqualTo("/stock/profile2"))
                .willReturn(okJson("{\"name\":\"Apple Inc\",\"finnhubIndustry\":\"Technology\",\"exchange\":\"NASDAQ\"}")));
        Profile p = service("k").profile("AAPL");
        assertThat(p.symbol()).isEqualTo("AAPL");
        assertThat(p.profile().path("finnhubIndustry").asString("")).isEqualTo("Technology");
    }

    @Test void blankKeyThrowsUnavailable() {
        assertThatThrownBy(() -> service("").profile("AAPL")).isInstanceOf(MarketDataException.class);
    }

    @Test void emptyBodyThrowsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/stock/profile2")).willReturn(okJson("{}")));
        assertThatThrownBy(() -> service("k").profile("AAPL")).isInstanceOf(MarketDataException.class);
    }

    @Test void nonUsSymbolSkipsFinnhubAndReturnsEmptyNonNullProfile() {
        Profile p = service("k").profile("SAP.DE");
        assertThat(p).isNotNull();
        assertThat(p.symbol()).isEqualTo("SAP.DE");
        assertThat(p.profile()).isNotNull();
        wm.verify(0, getRequestedFor(urlPathEqualTo("/stock/profile2")));
    }

    @Test void usSymbolStillCallsFinnhub() {
        wm.stubFor(get(urlPathEqualTo("/stock/profile2"))
                .willReturn(okJson("{\"name\":\"Apple Inc\",\"finnhubIndustry\":\"Technology\",\"exchange\":\"NASDAQ\"}")));
        Profile p = service("k").profile("AAPL");
        assertThat(p.symbol()).isEqualTo("AAPL");
        wm.verify(1, getRequestedFor(urlPathEqualTo("/stock/profile2")));
    }
}
