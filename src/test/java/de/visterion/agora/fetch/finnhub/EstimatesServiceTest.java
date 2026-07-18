package de.visterion.agora.fetch.finnhub;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.agora.data.MarketDataException;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EstimatesServiceTest {
    static WireMockServer wm;
    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    private EstimatesService svc(String key) {
        return svc(key, mock(YahooCompanyDataSource.class));
    }

    private EstimatesService svc(String key, YahooCompanyDataSource yahoo) {
        return new EstimatesService(new FinnhubClient(RestClient.builder().baseUrl(wm.baseUrl()).build(), key),
                21600L, System::currentTimeMillis, 86400L, yahoo);
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

    @Test void nonUsSymbolSkipsFinnhub() {
        YahooCompanyDataSource yahoo = mock(YahooCompanyDataSource.class);
        when(yahoo.recommendations("SAP.DE")).thenReturn(List.of());
        List<Recommendation> recs = svc("k", yahoo).recommendations("SAP.DE");
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

    @Test void nonUs_returnsYahooData() {
        YahooCompanyDataSource yahoo = mock(YahooCompanyDataSource.class);
        List<Recommendation> yahooRecs = List.of(new Recommendation("2025-06-01", 3, 2, 1, 0, 0));
        when(yahoo.recommendations("SAP.DE")).thenReturn(yahooRecs);
        List<Recommendation> recs = svc("k", yahoo).recommendations("SAP.DE");
        assertThat(recs).isEqualTo(yahooRecs);
        wm.verify(0, getRequestedFor(urlPathEqualTo("/stock/recommendation")));
    }

    @Test void nonUs_yahooThrows_degradesToEmpty_andNotCached() {
        YahooCompanyDataSource yahoo = mock(YahooCompanyDataSource.class);
        when(yahoo.recommendations("SAP.DE"))
                .thenThrow(new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "yahoo down", null));
        EstimatesService svc = svc("k", yahoo);

        assertThat(svc.recommendations("SAP.DE")).isEmpty();
        assertThat(svc.recommendations("SAP.DE")).isEmpty();

        verify(yahoo, times(2)).recommendations("SAP.DE");
    }

    @Test void nonUs_genuineEmpty_isCached() {
        YahooCompanyDataSource yahoo = mock(YahooCompanyDataSource.class);
        when(yahoo.recommendations("SAP.DE")).thenReturn(List.of());
        EstimatesService svc = svc("k", yahoo);

        assertThat(svc.recommendations("SAP.DE")).isEmpty();
        assertThat(svc.recommendations("SAP.DE")).isEmpty();

        verify(yahoo, times(1)).recommendations("SAP.DE");
    }

    @Test void nonUs_withoutFinnhubKey_usesYahoo_noThrow() {
        YahooCompanyDataSource yahoo = mock(YahooCompanyDataSource.class);
        List<Recommendation> yahooRecs = List.of(new Recommendation("2025-06-01", 1, 1, 1, 1, 1));
        when(yahoo.recommendations("SAP.DE")).thenReturn(yahooRecs);

        List<Recommendation> recs = svc("", yahoo).recommendations("SAP.DE");

        assertThat(recs).isEqualTo(yahooRecs);
    }

    @Test void us_unchanged() {
        YahooCompanyDataSource yahoo = mock(YahooCompanyDataSource.class);
        wm.stubFor(get(urlPathEqualTo("/stock/recommendation"))
                .withQueryParam("symbol", equalTo("AAPL"))
                .willReturn(okJson("""
                    [{"period":"2025-06-01","strongBuy":10,"buy":15,"hold":5,"sell":1,"strongSell":0}]
                    """)));
        List<Recommendation> recs = svc("k", yahoo).recommendations("AAPL");
        assertThat(recs).hasSize(1);
        verifyNoInteractions(yahoo);
    }
}
