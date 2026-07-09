package de.visterion.agora.data;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.agora.trading.saxo.SaxoDataAccess;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class SaxoMarketDataProviderTest {
    static WireMockServer wm;
    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    static final String SAP_XETR_INFOPRICE = """
        {
          "AssetType": "Stock",
          "DisplayAndFormat": {"Currency": "EUR", "Decimals": 2, "Description": "SAP SE", "Symbol": "SAPG:xetr"},
          "LastUpdated": "2026-07-09T21:48:47.625000Z",
          "PriceInfo": {"High": 138.12, "Low": 134.1, "NetChange": -0.22, "PercentChange": -0.16},
          "Quote": {"Amount": 0, "Ask": 137.66, "Bid": 137.62, "DelayedByMinutes": 15,
                    "ErrorCode": "None", "MarketState": "Closed", "Mid": 137.64,
                    "PriceTypeAsk": "OldIndicative", "PriceTypeBid": "OldIndicative"},
          "Uic": 1126
        }
        """;

    private SaxoMarketDataProvider provider(boolean withBearer) {
        SaxoDataAccess access = new SaxoDataAccess(
                RestClient.builder().baseUrl(wm.baseUrl()).build(),
                () -> withBearer ? Optional.of("Bearer t") : Optional.empty());
        return new SaxoMarketDataProvider(access, new SaxoDataSymbolResolver(access, () -> 0L));
    }

    private void stubSapSearch() {
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments")).willReturn(okJson("""
            {"Data":[{"AssetType":"Stock","CurrencyCode":"EUR","ExchangeId":"FSE","Identifier":1126,"Symbol":"SAPG:xetr"}]}
            """)));
    }

    @Test void nameIsSaxo() { assertThat(provider(true).name()).isEqualTo("saxo"); }

    @Test void quoteMapsMidPercentChangeAndCurrency() {
        stubSapSearch();
        wm.stubFor(get(urlPathEqualTo("/trade/v1/infoprices"))
                .withQueryParam("Uic", equalTo("1126"))
                .withQueryParam("AssetType", equalTo("Stock"))
                .withQueryParam("FieldGroups", equalTo("Quote,PriceInfo,DisplayAndFormat"))
                .willReturn(okJson(SAP_XETR_INFOPRICE)));
        Quote q = provider(true).quote("SAP.DE");
        assertThat(q.symbol()).isEqualTo("SAP.DE");
        assertThat(q.price()).isEqualByComparingTo("137.64");
        assertThat(q.dayChangePercent()).isEqualByComparingTo("-0.16");
        assertThat(q.currency()).isEqualTo("EUR");
    }

    @Test void quoteWithoutBearerIsUnavailable() {
        assertThatThrownBy(() -> provider(false).quote("SAP.DE"))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE));
    }

    @Test void quoteNoAccessPriceTypeIsUnavailable() {
        stubSapSearch();
        wm.stubFor(get(urlPathEqualTo("/trade/v1/infoprices")).willReturn(okJson("""
            {"Quote": {"Mid": 137.64, "PriceTypeAsk": "NoAccess", "PriceTypeBid": "NoAccess"},
             "DisplayAndFormat": {"Currency": "EUR"}, "Uic": 1126}
            """)));
        assertThatThrownBy(() -> provider(true).quote("SAP.DE"))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE));
    }

    @Test void quoteMissingMidIsUnavailable() {
        stubSapSearch();
        wm.stubFor(get(urlPathEqualTo("/trade/v1/infoprices")).willReturn(okJson("""
            {"Quote": {"ErrorCode": "None"}, "DisplayAndFormat": {"Currency": "EUR"}, "Uic": 1126}
            """)));
        assertThatThrownBy(() -> provider(true).quote("SAP.DE"))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE));
    }

    @Test void quoteMissingCurrencyIsUnavailable() {
        stubSapSearch();
        wm.stubFor(get(urlPathEqualTo("/trade/v1/infoprices")).willReturn(okJson("""
            {"Quote": {"Mid": 137.64}, "Uic": 1126}
            """)));
        assertThatThrownBy(() -> provider(true).quote("SAP.DE"))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE));
    }

    @Test void quoteHttpErrorIsUnavailable() {
        stubSapSearch();
        wm.stubFor(get(urlPathEqualTo("/trade/v1/infoprices")).willReturn(status(500)));
        assertThatThrownBy(() -> provider(true).quote("SAP.DE"))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE));
    }
}
