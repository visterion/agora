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
import static org.mockito.Mockito.*;

class ProfileServiceTest {

    private WireMockServer wm;

    @BeforeEach void start() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
    }

    @AfterEach void stop() { wm.stop(); }

    private ProfileService service(String key) {
        return service(key, mock(YahooCompanyDataSource.class));
    }

    private ProfileService service(String key, YahooCompanyDataSource yahoo) {
        RestClient http = RestClient.builder().baseUrl(wm.baseUrl()).build();
        FinnhubClient client = new FinnhubClient(http, key);
        return new ProfileService(client, 120L, System::currentTimeMillis, 604800L, yahoo);
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

    @Test void nonUsSymbolSkipsFinnhub() {
        YahooCompanyDataSource yahoo = mock(YahooCompanyDataSource.class);
        var profileNode = new tools.jackson.databind.ObjectMapper().createObjectNode();
        when(yahoo.profile("SAP.DE")).thenReturn(new Profile("SAP.DE", profileNode));

        Profile p = service("k", yahoo).profile("SAP.DE");

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

    @Test void nonUs_returnsYahooProfile() {
        YahooCompanyDataSource yahoo = mock(YahooCompanyDataSource.class);
        var profileNode = new tools.jackson.databind.ObjectMapper().createObjectNode().put("finnhubIndustry", "Technology");
        when(yahoo.profile("SAP.DE")).thenReturn(new Profile("SAP.DE", profileNode));

        Profile p = service("k", yahoo).profile("SAP.DE");

        assertThat(p.profile().path("finnhubIndustry").asString("")).isEqualTo("Technology");
        wm.verify(0, getRequestedFor(urlPathEqualTo("/stock/profile2")));
    }

    @Test void nonUs_yahooThrows_degradesToEmptyNonNull_notCached() {
        YahooCompanyDataSource yahoo = mock(YahooCompanyDataSource.class);
        when(yahoo.profile("SAP.DE"))
                .thenThrow(new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "yahoo down", null));
        ProfileService svc = service("k", yahoo);

        Profile p1 = svc.profile("SAP.DE");
        assertThat(p1).isNotNull();
        assertThat(p1.symbol()).isEqualTo("SAP.DE");
        assertThat(p1.profile()).isNotNull();
        assertThat(p1.profile().isEmpty()).isTrue();

        Profile p2 = svc.profile("SAP.DE");
        assertThat(p2.profile().isEmpty()).isTrue();

        verify(yahoo, times(2)).profile("SAP.DE");
    }

    @Test void nonUs_withoutFinnhubKey_usesYahoo() {
        YahooCompanyDataSource yahoo = mock(YahooCompanyDataSource.class);
        var profileNode = new tools.jackson.databind.ObjectMapper().createObjectNode().put("finnhubIndustry", "Technology");
        when(yahoo.profile("SAP.DE")).thenReturn(new Profile("SAP.DE", profileNode));

        Profile p = service("", yahoo).profile("SAP.DE");

        assertThat(p.profile().path("finnhubIndustry").asString("")).isEqualTo("Technology");
    }

    @Test void us_unchanged() {
        YahooCompanyDataSource yahoo = mock(YahooCompanyDataSource.class);
        wm.stubFor(get(urlPathEqualTo("/stock/profile2"))
                .willReturn(okJson("{\"name\":\"Apple Inc\",\"finnhubIndustry\":\"Technology\",\"exchange\":\"NASDAQ\"}")));

        Profile p = service("k", yahoo).profile("AAPL");

        assertThat(p.symbol()).isEqualTo("AAPL");
        verifyNoInteractions(yahoo);
    }
}
