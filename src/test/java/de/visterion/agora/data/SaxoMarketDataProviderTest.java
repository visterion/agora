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

    static final String CHART_BARS = """
        {"Data":[
          {"Close":310.66,"High":315.48,"Interest":0.0,"Low":310.15,"Open":315.18,"Time":"2026-07-07T00:00:00Z","Volume":42083264.0},
          {"Close":313.39,"High":314.82,"Interest":0.0,"Low":307.05,"Open":311.91,"Time":"2026-07-08T00:00:00Z","Volume":41240088.0},
          {"Close":316.22,"High":316.53,"Interest":0.0,"Low":308.16,"Open":310.34,"Time":"2026-07-09T00:00:00Z","Volume":47952976.0}
        ],"DataVersion":29721846}
        """;

    private void stubAccounts() {
        wm.stubFor(get(urlPathEqualTo("/port/v1/accounts/me")).willReturn(okJson("""
            {"Data":[{"AccountKey":"ACC-KEY","AccountType":"Normal","Active":true}]}
            """)));
    }

    @Test void ohlcMapsBarsOldestFirst() {
        stubSapSearch();
        stubAccounts();
        wm.stubFor(get(urlPathEqualTo("/chart/v3/charts"))
                .withQueryParam("Uic", equalTo("1126"))
                .withQueryParam("AssetType", equalTo("Stock"))
                .withQueryParam("Horizon", equalTo("1440"))
                .withQueryParam("Count", equalTo("3"))
                .withQueryParam("AccountKey", equalTo("ACC-KEY"))
                .willReturn(okJson(CHART_BARS)));
        var bars = provider(true).ohlc("SAP.DE", 3);
        assertThat(bars).hasSize(3);
        assertThat(bars.get(0).date()).isEqualTo("2026-07-07");
        assertThat(bars.get(0).open()).isEqualByComparingTo("315.18");
        assertThat(bars.get(0).volume()).isEqualTo(42083264L);
        assertThat(bars.get(2).date()).isEqualTo("2026-07-09");
        assertThat(bars.get(2).close()).isEqualByComparingTo("316.22");
    }

    @Test void ohlcTrimsToRequestedDays() {
        stubSapSearch();
        stubAccounts();
        wm.stubFor(get(urlPathEqualTo("/chart/v3/charts")).willReturn(okJson(CHART_BARS)));
        var bars = provider(true).ohlc("SAP.DE", 2);
        assertThat(bars).hasSize(2);
        assertThat(bars.get(0).date()).isEqualTo("2026-07-08");   // oldest trimmed away
    }

    @Test void ohlcWithoutAccountKeyIsUnavailable() {
        stubSapSearch();
        wm.stubFor(get(urlPathEqualTo("/port/v1/accounts/me")).willReturn(status(401)));
        assertThatThrownBy(() -> provider(true).ohlc("SAP.DE", 3))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE));
    }

    @Test void ohlcHttpErrorIsUnavailable() {
        stubSapSearch();
        stubAccounts();
        wm.stubFor(get(urlPathEqualTo("/chart/v3/charts")).willReturn(status(429)));
        assertThatThrownBy(() -> provider(true).ohlc("SAP.DE", 3))
                .isInstanceOfSatisfying(MarketDataException.class,
                        e -> assertThat(e.kind()).isEqualTo(MarketDataException.Kind.UNAVAILABLE));
    }
}
