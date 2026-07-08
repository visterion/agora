package de.visterion.agora.trading.saxo;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.agora.trading.BrokerException;
import de.visterion.agora.trading.ConnectionConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class SaxoBrokerProviderTest {

    static WireMockServer wm;
    @TempDir Path dir;
    final AtomicLong now = new AtomicLong(1_000_000L);
    SaxoTokenStore store;
    SaxoBrokerProvider provider;

    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }

    @BeforeEach
    void setUp() {
        wm.resetAll();
        ConnectionConfig cfg = new ConnectionConfig();
        cfg.setProvider("saxo");
        cfg.setEnvironment(ConnectionConfig.Environment.PAPER);
        cfg.setBaseUrl(wm.baseUrl());
        cfg.setKeyId("k"); cfg.setSecret("s");
        store = new SaxoTokenStore("saxo-sim", dir, now::get);
        store.update("acc-token", 1200, "ref");
        provider = new SaxoBrokerProvider(cfg, store, RestClient.builder().baseUrl(wm.baseUrl()).build());
        stubAccounts();
    }

    private void stubAccounts() {
        wm.stubFor(get(urlEqualTo("/port/v1/accounts/me")).willReturn(okJson("""
            {"Data":[{"AccountKey":"Acc+Key/1==","ClientKey":"Cli+Key/1==","AccountId":"123"}]}
            """)));
    }

    // ---- probe ----

    @Test
    void probe_200_quietAndSendsBearer() {
        wm.stubFor(get(urlEqualTo("/root/v1/user")).willReturn(okJson("{\"UserId\":\"u\"}")));
        assertThatCode(() -> provider.probe()).doesNotThrowAnyException();
        wm.verify(getRequestedFor(urlEqualTo("/root/v1/user"))
                .withHeader("Authorization", equalTo("Bearer acc-token")));
    }

    @Test
    void probe_401_throwsUnavailable() {
        wm.stubFor(get(urlEqualTo("/root/v1/user")).willReturn(aResponse().withStatus(401)));
        assertThatThrownBy(() -> provider.probe()).isInstanceOf(BrokerException.class);
    }

    @Test
    void noValidTokenIsUnavailableWithReAuthHint() {
        now.addAndGet(2_000_000L);                    // access expired
        assertThatThrownBy(() -> provider.probe())
                .isInstanceOf(BrokerException.class)
                .hasMessageContaining("/auth/saxo/login")
                .hasMessageNotContaining("acc-token");
    }

    // ---- account context ----

    @Test
    void multipleAccountsWithoutExtraKeyIsUnavailable() {
        wm.stubFor(get(urlEqualTo("/port/v1/accounts/me")).willReturn(okJson("""
            {"Data":[{"AccountKey":"A==","ClientKey":"C=="},{"AccountKey":"B==","ClientKey":"C=="}]}
            """)));
        assertThatThrownBy(() -> provider.account())
                .isInstanceOf(BrokerException.class)
                .hasMessageContaining("account-key");
    }

    // ---- reads ----

    @Test
    void accountMapsBalances() {
        wm.stubFor(get(urlPathEqualTo("/port/v1/balances")).willReturn(okJson("""
            {"CashBalance":10000.5,"TotalValue":10500.25,"Currency":"USD","MarginAvailableForTrading":9800.0}
            """)));
        var a = provider.account();
        assertThat(a.accountId()).isEqualTo("Acc+Key/1==");
        assertThat(a.equity()).isEqualByComparingTo("10500.25");
        assertThat(a.cash()).isEqualByComparingTo("10000.5");
        assertThat(a.buyingPower()).isEqualByComparingTo("9800.0");
        assertThat(a.currency()).isEqualTo("USD");
        wm.verify(getRequestedFor(urlPathEqualTo("/port/v1/balances"))
                .withQueryParam("ClientKey", equalTo("Cli%2BKey%2F1%3D%3D"))
                .withQueryParam("AccountKey", equalTo("Acc%2BKey%2F1%3D%3D")));
    }

    @Test
    void positionsMapNetPositions() {
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionId":"AAPL:xnas__Stock",
                      "NetPositionBase":{"Amount":10.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":150.0,"CurrentMarketValue":1510.0,
                                         "ProfitLossOnTrade":100.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas","Currency":"USD"}}]}
            """)));
        var ps = provider.positions();
        assertThat(ps).hasSize(1);
        assertThat(ps.get(0).symbol()).isEqualTo("AAPL");
        assertThat(ps.get(0).qty()).isEqualByComparingTo("10");
        assertThat(ps.get(0).avgEntryPrice()).isEqualByComparingTo("150.0");
        assertThat(ps.get(0).unrealizedPl()).isEqualByComparingTo("100.0");
        assertThat(ps.get(0).currency()).isEqualTo("USD");
        wm.verify(getRequestedFor(urlPathEqualTo("/port/v1/netpositions"))
                .withQueryParam("ClientKey", equalTo("Cli%2BKey%2F1%3D%3D"))
                .withQueryParam("AccountKey", equalTo("Acc%2BKey%2F1%3D%3D"))
                .withQueryParam("FieldGroups", equalTo("NetPositionBase%2CNetPositionView%2CDisplayAndFormat")));
    }

    @Test
    void ordersMapOpenOrdersAndFilterClientSide() {
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[{"OrderId":"5001","Uic":211,"AssetType":"Stock","BuySell":"Buy","Amount":10.0,
                      "OpenOrderType":"Limit","Status":"Working","ExternalReference":"ref-1",
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        var all = provider.orders(null);
        assertThat(all).hasSize(1);
        assertThat(all.get(0).brokerOrderId()).isEqualTo("5001");
        assertThat(all.get(0).clientRef()).isEqualTo("ref-1");
        assertThat(all.get(0).symbol()).isEqualTo("AAPL");
        assertThat(all.get(0).side()).isEqualTo("buy");
        assertThat(provider.orders("working")).hasSize(1);
        assertThat(provider.orders("filled")).isEmpty();     // client-side filter
    }

    @Test
    void orderByClientRefFindsMatchOr404() {
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[{"OrderId":"5001","BuySell":"Buy","Amount":1.0,"OpenOrderType":"Limit",
                      "Status":"Working","ExternalReference":"ref-1",
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        assertThat(provider.orderByClientRef("ref-1").brokerOrderId()).isEqualTo("5001");
        assertThatThrownBy(() -> provider.orderByClientRef("ref-x"))
                .isInstanceOf(BrokerException.class)
                .extracting(e -> ((BrokerException) e).kind())
                .isEqualTo(BrokerException.Kind.NOT_FOUND);
    }

    @Test
    void serverErrorOnReadIsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(aResponse().withStatus(503)));
        assertThatThrownBy(() -> provider.positions()).isInstanceOf(BrokerException.class);
    }
}
