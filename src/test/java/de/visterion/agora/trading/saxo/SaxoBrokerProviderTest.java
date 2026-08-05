package de.visterion.agora.trading.saxo;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import de.visterion.agora.trading.BrokerException;
import de.visterion.agora.trading.ConnectionConfig;
import de.visterion.agora.trading.OrderResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.util.List;
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
    ConnectionConfig cfg;

    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }

    @BeforeEach
    void setUp() {
        wm.resetAll();
        cfg = new ConnectionConfig();
        cfg.setProvider("saxo");
        cfg.setEnvironment(ConnectionConfig.Environment.PAPER);
        cfg.setBaseUrl(wm.baseUrl());
        cfg.setKeyId("k"); cfg.setSecret("s");
        store = new SaxoTokenStore("saxo-sim", dir, now::get);
        store.update("acc-token", 1200, "ref");
        provider = new SaxoBrokerProvider(cfg, store, RestClient.builder().baseUrl(wm.baseUrl()).build(),
                resolver());
        provider.legLookupDelayMillis = 0;   // don't actually sleep in tests
        provider.farStopDelayMillis = 0;     // ditto for the pre-fallback spacing
        stubAccounts();
    }

    private SaxoInstrumentResolver resolver() {
        return new SaxoInstrumentResolver(RestClient.builder().baseUrl(wm.baseUrl()).build(),
                () -> "Bearer acc-token", null, null, 86_400_000L, now::get);
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
        store.markDead("test");                       // dead connection needs re-auth
        assertThatThrownBy(() -> provider.probe())
                .isInstanceOf(BrokerException.class)
                .hasMessageContaining("/auth/saxo/login")
                .hasMessageNotContaining("acc-token");
    }

    @Test
    void probePropagatesNotReadyWhenAccessPendingButRefreshPresent() {
        now.addAndGet(1_300_000L);   // expire the access token set in setUp; refresh remains
        assertThatThrownBy(() -> provider.probe())
                .isInstanceOfSatisfying(BrokerException.class,
                        e -> assertThat(e.kind()).isEqualTo(BrokerException.Kind.NOT_READY));
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
                .withQueryParam("ClientKey", equalTo("Cli+Key/1=="))
                .withQueryParam("AccountKey", equalTo("Acc+Key/1==")));
    }

    @Test
    void positionsMapNetPositions() {
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionId":"AAPL:xnas__Stock",
                      "NetPositionBase":{"Amount":10.0,"Uic":211,"AssetType":"Stock","ValueDate":"2026-07-10"},
                      "NetPositionView":{"AverageOpenPrice":150.0,"Exposure":1510.0,
                                         "ProfitLossOnTrade":100.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas","Currency":"USD","Description":"PriceSmart Inc"}}]}
            """)));
        var ps = provider.positions();
        assertThat(ps).hasSize(1);
        assertThat(ps.get(0).symbol()).isEqualTo("AAPL");
        assertThat(ps.get(0).description()).isEqualTo("PriceSmart Inc");
        assertThat(ps.get(0).qty()).isEqualByComparingTo("10");
        assertThat(ps.get(0).avgEntryPrice()).isEqualByComparingTo("150.0");
        assertThat(ps.get(0).unrealizedPl()).isEqualByComparingTo("100.0");
        // Exposure is live (non-zero) here → used verbatim as market value.
        assertThat(ps.get(0).marketValue()).isEqualByComparingTo("1510.0");
        assertThat(ps.get(0).currency()).isEqualTo("USD");
        assertThat(ps.get(0).assetType()).isEqualTo("Stock");
        assertThat(ps.get(0).valueDate()).isEqualTo("2026-07-10");
        // Saxo encodes direction in the SIGN of NetPositionBase.Amount: positive = long.
        assertThat(ps.get(0).side()).isEqualTo("BUY");
        wm.verify(getRequestedFor(urlPathEqualTo("/port/v1/netpositions"))
                .withQueryParam("ClientKey", equalTo("Cli+Key/1=="))
                .withQueryParam("AccountKey", equalTo("Acc+Key/1=="))
                .withQueryParam("FieldGroups", equalTo("NetPositionBase,NetPositionView,DisplayAndFormat")));
    }

    /**
     * Saxo has no {@code side} field on a net position — long/short lives in the SIGN of
     * {@code NetPositionBase.Amount}. Same convention the shipped flatten() path already
     * relies on (SaxoBrokerProvider: {@code opposite = amount.signum() > 0 ? "Sell" : "Buy"}),
     * i.e. a negative Amount is a short and is closed by BUYing it back.
     */
    @Test
    void positionsSideIsSellForNegativeAmount() {
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionId":"AAPL:xnas__Stock",
                      "NetPositionBase":{"Amount":-10.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":150.0,"Exposure":-1510.0,
                                         "ProfitLossOnTrade":100.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas","Currency":"USD"}}]}
            """)));
        var ps = provider.positions();
        assertThat(ps).hasSize(1);
        assertThat(ps.get(0).side()).isEqualTo("SELL");
        // qty keeps the raw signed amount — side is derived from it, it does not replace it.
        assertThat(ps.get(0).qty()).isEqualByComparingTo("-10");
    }

    /**
     * Amount == 0 carries no direction (a flat net position). Reporting "BUY" there would be
     * a guess; the field stays null so the consumer can see the difference.
     */
    @Test
    void positionsSideIsNullForZeroAmount() {
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"__count":1,"Data":[{
              "DisplayAndFormat":{"Currency":"USD","Symbol":"PSMT:xnas"},
              "NetPositionBase":{"Amount":0.0,"AssetType":"Stock"},
              "NetPositionView":{"AverageOpenPrice":193.87,"Exposure":0.0,"ProfitLossOnTrade":0.0}}]}
            """)));
        assertThat(provider.positions().get(0).side()).isNull();
    }

    @Test
    void positionsDeriveMarketValueWhenExposureZero() {
        // Delayed SIM/paper feed: Exposure and CurrentPrice read 0 (CurrentPriceType "None"),
        // but AverageOpenPrice and ProfitLossOnTrade are populated. Market value is
        // reconstructed as qty*avgOpen + P/L = 5*193.87 + (-6.55) = 962.80 — the real PSMT
        // case observed in prod 2026-07-13.
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionId":"PSMT:xnas__Stock",
                      "NetPositionBase":{"Amount":5.0,"Uic":123,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":193.87,"Exposure":0.0,"CurrentPrice":0.0,
                                         "CurrentPriceType":"None","ProfitLossOnTrade":-6.55,
                                         "ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"PSMT:xnas","Currency":"USD"}}]}
            """)));
        var ps = provider.positions();
        assertThat(ps).hasSize(1);
        assertThat(ps.get(0).symbol()).isEqualTo("PSMT");
        assertThat(ps.get(0).marketValue()).isEqualByComparingTo("962.80");
        assertThat(ps.get(0).unrealizedPl()).isEqualByComparingTo("-6.55");
    }

    @Test
    void positionsDerivePerUnitMarketPriceAndOpenOrders() {
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"__count":1,"Data":[{
              "DisplayAndFormat":{"Currency":"USD","Description":"PricesSmart Inc.","Symbol":"PSMT:xnas"},
              "NetPositionBase":{"Amount":5.0,"AssetType":"Stock","OpenOrdersCount":1,"ValueDate":"2026-07-14"},
              "NetPositionView":{"AverageOpenPrice":193.87,"CurrentPrice":0.0,"Exposure":962.80,
                                 "ExposureCurrency":"USD","ProfitLossOnTrade":-13.20}}]}
            """)));
        var out = provider.positions();
        assertThat(out.get(0).marketValue()).isEqualByComparingTo("962.80");
        // total must NEVER leak into the per-unit field:
        assertThat(out.get(0).marketPrice()).isEqualByComparingTo("192.56");
        assertThat(out.get(0).openOrdersCount()).isEqualTo(1);
    }

    @Test
    void zeroQtyYieldsNullMarketPrice() {
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"__count":1,"Data":[{
              "DisplayAndFormat":{"Currency":"USD","Symbol":"PSMT:xnas"},
              "NetPositionBase":{"Amount":0.0,"AssetType":"Stock"},
              "NetPositionView":{"AverageOpenPrice":193.87,"Exposure":0.0,"ProfitLossOnTrade":0.0}}]}
            """)));
        assertThat(provider.positions().get(0).marketPrice()).isNull();
        assertThat(provider.positions().get(0).openOrdersCount()).isEqualTo(0);
    }

    @Test
    void closedPositionsMapsRealFillPricesAndClientRef() {
        wm.stubFor(get(urlPathEqualTo("/port/v1/closedpositions")).willReturn(okJson("""
            {"__count":1,"Data":[{
              "ClosedPosition":{"Uic":36313,"AssetType":"Stock","Amount":3.0,
                                 "OpenPrice":364.35,"ClosingPrice":364.10,
                                 "ClosedProfitLoss":-0.25,
                                 "OpeningExternalReferenceId":"sig-1",
                                 "ClosingExternalReferenceId":"sig-1-close"},
              "DisplayAndFormat":{"Symbol":"ISRG:xnas","Currency":"USD"},
              "NetPositionId":"ISRG:xnas__Stock"}]}
            """)));
        var cps = provider.closedPositions();
        assertThat(cps).hasSize(1);
        assertThat(cps.get(0).symbol()).isEqualTo("ISRG");
        assertThat(cps.get(0).uic()).isEqualTo(36313L);
        assertThat(cps.get(0).openPrice()).isEqualByComparingTo("364.35");
        assertThat(cps.get(0).closePrice()).isEqualByComparingTo("364.10");
        assertThat(cps.get(0).amount()).isEqualByComparingTo("3.0");
        assertThat(cps.get(0).profitLoss()).isEqualByComparingTo("-0.25");
        assertThat(cps.get(0).clientRef()).isEqualTo("sig-1");
        wm.verify(getRequestedFor(urlPathEqualTo("/port/v1/closedpositions"))
                .withQueryParam("ClientKey", equalTo("Cli+Key/1=="))
                .withQueryParam("AccountKey", equalTo("Acc+Key/1==")));
    }

    @Test
    void closedPositionsClientRefNullWhenExternalReferenceAbsent() {
        wm.stubFor(get(urlPathEqualTo("/port/v1/closedpositions")).willReturn(okJson("""
            {"__count":1,"Data":[{
              "ClosedPosition":{"Uic":211,"AssetType":"Stock","Amount":10.0,
                                 "OpenPrice":150.0,"ClosingPrice":155.0,"ClosedProfitLoss":50.0},
              "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        var cps = provider.closedPositions();
        assertThat(cps).hasSize(1);
        assertThat(cps.get(0).clientRef()).isNull();
    }

    @Test void closedPositionsMapsTimestampsAndOpeningPositionId() {
        wm.stubFor(get(urlPathEqualTo("/port/v1/closedpositions")).willReturn(okJson("""
            {"__count":1,"Data":[{
              "ClosedPosition":{"Uic":36313,"AssetType":"Stock","Amount":3.0,
                                 "OpenPrice":364.35,"ClosingPrice":364.10,"ClosedProfitLoss":-0.25,
                                 "ExecutionTimeOpen":"2026-07-01T09:00:00.000000Z",
                                 "ExecutionTimeClose":"2026-07-01T15:30:00.000000Z",
                                 "OpeningPositionId":"998877","OpeningExternalReferenceId":"sig-1"},
              "DisplayAndFormat":{"Symbol":"ISRG:xnas"}}]}
            """)));
        var cps = provider.closedPositions(null, null);
        assertThat(cps).hasSize(1);
        assertThat(cps.get(0).openTime()).isEqualTo("2026-07-01T09:00:00.000000Z");
        assertThat(cps.get(0).closeTime()).isEqualTo("2026-07-01T15:30:00.000000Z");
        assertThat(cps.get(0).openingPositionId()).isEqualTo(998877L);
    }

    @Test void closedPositionsRangeFiltersTemporallyOnCloseTime() {
        wm.stubFor(get(urlPathEqualTo("/port/v1/closedpositions")).willReturn(okJson("""
            {"__count":2,"Data":[
              {"ClosedPosition":{"Uic":1,"Amount":1.0,"OpenPrice":1,"ClosingPrice":1,"ClosedProfitLoss":0,
                                 "ExecutionTimeClose":"2026-06-30T23:59:59Z"},
               "DisplayAndFormat":{"Symbol":"A:xnas"}},
              {"ClosedPosition":{"Uic":2,"Amount":1.0,"OpenPrice":1,"ClosingPrice":1,"ClosedProfitLoss":0,
                                 "ExecutionTimeClose":"2026-07-01T00:30:00.000Z"},
               "DisplayAndFormat":{"Symbol":"B:xnas"}}]}
            """)));
        // from with a +02:00 offset that is temporally AFTER the first row but BEFORE the second:
        // 2026-07-01T02:15:00+02:00 == 2026-07-01T00:15:00Z, which sits strictly between
        // A's 2026-06-30T23:59:59Z and B's 2026-07-01T00:30:00Z on the instant timeline
        // (a naive lexicographic string compare would get this wrong).
        var cps = provider.closedPositions("2026-07-01T02:15:00+02:00", null); // == 2026-07-01T00:15:00Z
        assertThat(cps).extracting(de.visterion.agora.trading.ClosedPosition::symbol).containsExactly("B");
    }

    @Test void closedPositionsNullCloseTimeKeptOnlyWithoutRange() {
        wm.stubFor(get(urlPathEqualTo("/port/v1/closedpositions")).willReturn(okJson("""
            {"__count":1,"Data":[{
              "ClosedPosition":{"Uic":3,"Amount":1.0,"OpenPrice":1,"ClosingPrice":1,"ClosedProfitLoss":0},
              "DisplayAndFormat":{"Symbol":"C:xnas"}}]}
            """)));
        assertThat(provider.closedPositions(null, null)).hasSize(1);
        assertThat(provider.closedPositions("2026-01-01T00:00:00Z", null)).isEmpty();
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
    void ordersFlattensBracketLegsWithRoleAndParentId() {
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"9001","OpenOrderType":"Limit","Status":"Working","Uic":211,"AssetType":"Stock",
               "BuySell":"Buy","Amount":1.0,"OrderRelation":"IfDoneMaster",
               "DisplayAndFormat":{"Symbol":"AAPL:xnas"},
               "RelatedOpenOrders":[
                 {"OrderId":"9002","OpenOrderType":"Limit","Status":"NotWorking","Amount":1.0},
                 {"OrderId":"9003","OpenOrderType":"StopIfTraded","Status":"NotWorking","Amount":1.0}]}
            ]}
            """)));

        var all = provider.orders(null);

        assertThat(all).hasSize(3);
        assertThat(all.get(0).brokerOrderId()).isEqualTo("9001");
        assertThat(all.get(0).role()).isEqualTo("entry");
        assertThat(all.get(0).parentId()).isNull();

        var tpLeg = all.get(1);
        assertThat(tpLeg.brokerOrderId()).isEqualTo("9002");
        assertThat(tpLeg.role()).isEqualTo("take_profit");
        assertThat(tpLeg.parentId()).isEqualTo("9001");

        var slLeg = all.get(2);
        assertThat(slLeg.brokerOrderId()).isEqualTo("9003");
        assertThat(slLeg.role()).isEqualTo("stop_loss");
        assertThat(slLeg.parentId()).isEqualTo("9001");
    }

    @Test
    void ordersOpen_parsesLimitAndStopPriceFromSaxo() {
        // Real Saxo SIM bracket response captured in prod logs (see docstring on
        // SaxoBrokerProvider#classifyPrice): parent carries its price in "Price", each leg in
        // RelatedOpenOrders carries it in "OrderPrice".
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"__count":1,"Data":[{"OpenOrderType":"Limit","OrderId":"5039279121","OrderRelation":"IfDoneMaster",
              "Price":182.53,
              "DisplayAndFormat":{"Symbol":"STT:xnys"},"BuySell":"Buy","Amount":6.0,"Status":"Working",
              "RelatedOpenOrders":[
                {"OpenOrderType":"Limit","OrderId":"5039279122","OrderPrice":226.03,"Status":"NotWorking"},
                {"OpenOrderType":"StopIfTraded","OrderId":"5039279123","OrderPrice":168.03,"Status":"NotWorking"}
              ]}]}
            """)));

        var all = provider.orders(null);
        assertThat(all).hasSize(3);

        var parent = all.get(0);
        assertThat(parent.brokerOrderId()).isEqualTo("5039279121");
        assertThat(parent.limitPrice()).isEqualByComparingTo("182.53");
        assertThat(parent.stopPrice()).isNull();

        var tpLeg = all.get(1);
        assertThat(tpLeg.brokerOrderId()).isEqualTo("5039279122");
        assertThat(tpLeg.limitPrice()).isEqualByComparingTo("226.03");
        assertThat(tpLeg.stopPrice()).isNull();

        var slLeg = all.get(2);
        assertThat(slLeg.brokerOrderId()).isEqualTo("5039279123");
        assertThat(slLeg.stopPrice()).isEqualByComparingTo("168.03");
        assertThat(slLeg.limitPrice()).isNull();
    }

    @Test
    void ordersOpen_mergesMutualOcoDuplicatesIntoOneEntryPerId() {
        // Hand-written synthetic shape of a filled bracket's surviving OCO pair: Saxo lists
        // BOTH legs as top-level Data entries, each carrying the OTHER leg in its own
        // RelatedOpenOrders. Ids/symbol are invented, not from any live account.
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"1000000001","OpenOrderType":"StopIfTraded","Status":"Working","Uic":911,
               "AssetType":"Stock","BuySell":"Sell","Amount":6.0,"OrderPrice":168.03,
               "ExternalReference":"ref-sl","DisplayAndFormat":{"Symbol":"SYNTH:xnys"},
               "RelatedOpenOrders":[
                 {"OrderId":"1000000002","OpenOrderType":"Limit","Status":"Working","Amount":6.0}]},
              {"OrderId":"1000000002","OpenOrderType":"Limit","Status":"Working","Uic":911,
               "AssetType":"Stock","BuySell":"Sell","Amount":6.0,"OrderPrice":226.03,
               "ExternalReference":"ref-tp","DisplayAndFormat":{"Symbol":"SYNTH:xnys"},
               "RelatedOpenOrders":[
                 {"OrderId":"1000000001","OpenOrderType":"StopIfTraded","Status":"Working","Amount":6.0}]}
            ]}
            """)));

        var all = provider.orders(null);

        assertThat(all).hasSize(2);
        var stop = all.get(0);
        assertThat(stop.brokerOrderId()).isEqualTo("1000000001");
        assertThat(stop.role()).isEqualTo("stop_loss");
        assertThat(stop.parentId()).isEqualTo("1000000002");
        assertThat(stop.side()).isEqualTo("sell");
        assertThat(stop.clientRef()).isEqualTo("ref-sl");

        var tp = all.get(1);
        assertThat(tp.brokerOrderId()).isEqualTo("1000000002");
        assertThat(tp.role()).isEqualTo("take_profit");
        assertThat(tp.parentId()).isEqualTo("1000000001");
        assertThat(tp.side()).isEqualTo("sell");
        assertThat(tp.clientRef()).isEqualTo("ref-tp");
    }

    @Test
    void ordersOpen_unfilledBracketChildrenNotDuplicated() {
        // Control case: an unfilled bracket's children are NOT separately present at top
        // level, so nothing collides on id and the merge must be a no-op — three orders.
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"1000000010","OpenOrderType":"Limit","Status":"Working","Uic":912,
               "AssetType":"Stock","BuySell":"Buy","Amount":7.0,"OrderRelation":"IfDoneMaster",
               "DisplayAndFormat":{"Symbol":"SYNTH:xnys"},
               "RelatedOpenOrders":[
                 {"OrderId":"1000000011","OpenOrderType":"Limit","Status":"NotWorking","Amount":7.0},
                 {"OrderId":"1000000012","OpenOrderType":"StopIfTraded","Status":"NotWorking","Amount":7.0}]}
            ]}
            """)));

        var all = provider.orders(null);

        assertThat(all).hasSize(3);
        assertThat(all.get(0).brokerOrderId()).isEqualTo("1000000010");
        assertThat(all.get(0).role()).isEqualTo("entry");
        assertThat(all.get(1).brokerOrderId()).isEqualTo("1000000011");
        assertThat(all.get(1).role()).isEqualTo("take_profit");
        assertThat(all.get(2).brokerOrderId()).isEqualTo("1000000012");
        assertThat(all.get(2).role()).isEqualTo("stop_loss");
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

    @Test void ordersNoRangeStillHitsOpenEndpoint() {  // C1 regression guard
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[{"OrderId":"5001","Uic":211,"BuySell":"Buy","Amount":10.0,"OpenOrderType":"Limit",
                      "Status":"Working","ExternalReference":"ref-1","DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        assertThat(provider.orders("working", null, null)).hasSize(1);
        assertThat(provider.orders("filled", null, null)).isEmpty();  // client-side filter, open endpoint
        wm.verify(0, getRequestedFor(urlPathEqualTo("/cs/v1/audit/orderactivities")));
    }

    @Test void ordersHistoryPathRoutesToAuditWithFills() {
        wm.stubFor(get(urlPathEqualTo("/cs/v1/audit/orderactivities")).willReturn(okJson("""
            {"Data":[{"OrderId":"7001","ExternalReference":"ref-h","BuySell":"Sell","Amount":4.0,
                      "OrderType":"Market","Status":"FinalFill","FilledAmount":4.0,"AveragePrice":151.25,
                      "ActivityTime":"2026-07-01T15:30:00.000000Z",
                      "DisplayAndFormat":{"Symbol":"ISRG:xnas"}}]}
            """)));
        var os = provider.orders("all", null, null);
        assertThat(os).hasSize(1);
        var o = os.get(0);
        assertThat(o.brokerOrderId()).isEqualTo("7001");
        assertThat(o.clientRef()).isEqualTo("ref-h");
        assertThat(o.symbol()).isEqualTo("ISRG");
        assertThat(o.side()).isEqualTo("sell");
        assertThat(o.filledQty()).isEqualByComparingTo("4.0");
        assertThat(o.avgFillPrice()).isEqualByComparingTo("151.25");
        assertThat(o.filledAt()).isEqualTo("2026-07-01T15:30:00.000000Z");
        assertThat(o.role()).isEqualTo("other");
        assertThat(o.parentId()).isNull();
        wm.verify(getRequestedFor(urlPathEqualTo("/cs/v1/audit/orderactivities"))
                .withQueryParam("EntryType", equalTo("Last")));
    }

    @Test void ordersRangeRoutesToAuditWithFromToDateTime() {
        wm.stubFor(get(urlPathEqualTo("/cs/v1/audit/orderactivities")).willReturn(okJson("{\"Data\":[]}")));
        provider.orders(null, "2026-07-01T00:00:00Z", "2026-07-02T00:00:00Z");
        wm.verify(getRequestedFor(urlPathEqualTo("/cs/v1/audit/orderactivities"))
                .withQueryParam("FromDateTime", equalTo("2026-07-01T00:00:00Z"))
                .withQueryParam("ToDateTime", equalTo("2026-07-02T00:00:00Z")));
    }

    @Test void ordersHistoryFromOnlyOmitsToDateTime() {
        wm.stubFor(get(urlPathEqualTo("/cs/v1/audit/orderactivities")).willReturn(okJson("{\"Data\":[]}")));
        provider.orders(null, "2026-07-01T00:00:00Z", null);
        wm.verify(getRequestedFor(urlPathEqualTo("/cs/v1/audit/orderactivities"))
                .withQueryParam("FromDateTime", equalTo("2026-07-01T00:00:00Z"))
                .withoutQueryParam("ToDateTime"));
    }

    @Test void ordersHistoryExplicitNullFillFieldsMapToNull() {
        wm.stubFor(get(urlPathEqualTo("/cs/v1/audit/orderactivities")).willReturn(okJson("""
            {"Data":[{"OrderId":"7002","ExternalReference":"ref-n","BuySell":"Buy","Amount":2.0,
                      "OrderType":"Market","Status":"Placed","FilledAmount":null,"AveragePrice":null,
                      "ActivityTime":"2026-07-01T15:30:00.000000Z",
                      "DisplayAndFormat":{"Symbol":"ISRG:xnas"}}]}
            """)));
        var os = provider.orders("all", null, null);
        assertThat(os).hasSize(1);
        assertThat(os.get(0).filledQty()).isNull();
        assertThat(os.get(0).avgFillPrice()).isNull();
    }

    @Test
    void serverErrorOnReadIsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(aResponse().withStatus(503)));
        assertThatThrownBy(() -> provider.positions()).isInstanceOf(BrokerException.class);
    }

    // ---- submitBracket ----

    private de.visterion.agora.trading.BracketOrderRequest bracketReq() {
        return new de.visterion.agora.trading.BracketOrderRequest(
                "AAPL", "buy", new java.math.BigDecimal("1"), "limit", "gtc",
                new java.math.BigDecimal("100"), new java.math.BigDecimal("90"), null,
                new java.math.BigDecimal("110"), "ref-1");
        // TP/SL near entry: Saxo enforces a proximity band (TooFarFromEntryOrder)
    }

    private void stubInstrument() {
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments")).willReturn(okJson("""
            {"Data":[{"Identifier":211,"AssetType":"Stock","Symbol":"AAPL:xnas"}]}
            """)));
    }

    @Test
    void submitBracketPostsEntryWithTwoRelatedOrders() {
        stubInstrument();
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).willReturn(okJson("""
            {"OrderId":"9001","Orders":[{"OrderId":"9002"},{"OrderId":"9003"}]}
            """)));

        var r = provider.submitBracket(bracketReq());

        assertThat(r.accepted()).isTrue();
        assertThat(r.brokerOrderId()).isEqualTo("9001");
        assertThat(r.clientRef()).isEqualTo("ref-1");
        wm.verify(postRequestedFor(urlEqualTo("/trade/v2/orders"))
                // X-Request-ID is a per-attempt dedupe key now, no longer the clientRef —
                // the clientRef lives on as ExternalReference in the body (asserted below).
                .withHeader("X-Request-ID", matching(".+"))
                .withRequestBody(matchingJsonPath("$.Uic", equalTo("211")))
                .withRequestBody(matchingJsonPath("$.BuySell", equalTo("Buy")))
                .withRequestBody(matchingJsonPath("$.OrderType", equalTo("Limit")))
                .withRequestBody(matchingJsonPath("$.ExternalReference", equalTo("ref-1")))
                .withRequestBody(matchingJsonPath("$.OrderDuration.DurationType", equalTo("GoodTillCancel")))
                .withRequestBody(matchingJsonPath("$.Orders[0].OrderType", equalTo("Limit")))
                .withRequestBody(matchingJsonPath("$.Orders[0].BuySell", equalTo("Sell")))
                .withRequestBody(matchingJsonPath("$.Orders[1].OrderType", equalTo("StopIfTraded"))));
    }

    @Test
    void bracketWithoutTakeProfitOmitsTheTakeProfitLeg() {
        // Entry + Stop, no take-profit. The capability existed only inside the far-stop
        // fallback so far; here it becomes the regular case (Dracul places tranche 2 without
        // a take-profit because Saxo rejects a 3R target with TooFarFromEntryOrder).
        stubInstrument();
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).willReturn(okJson("""
            {"OrderId":"9001","Orders":[{"OrderId":"9003"}]}
            """)));

        var req = new de.visterion.agora.trading.BracketOrderRequest(
                "AAPL", "buy", new java.math.BigDecimal("1"), "limit", "gtc",
                new java.math.BigDecimal("100"), new java.math.BigDecimal("90"), null,
                null, "ref-no-tp");

        var r = provider.submitBracket(req);

        assertThat(r.accepted()).isTrue();
        assertThat(r.brokerOrderId()).isEqualTo("9001");

        var posts = wm.findAll(postRequestedFor(urlEqualTo("/trade/v2/orders")));
        assertThat(posts).hasSize(1);
        var orders = new tools.jackson.databind.ObjectMapper()
                .readTree(posts.get(0).getBodyAsString()).path("Orders");
        assertThat(orders.size()).isEqualTo(1);
        assertThat(orders.get(0).path("OrderType").asString()).isIn("StopIfTraded", "StopLimit");
        assertThat(orders.get(0).path("OrderPrice").asString()).isEqualTo("90");
        assertThat(orders.get(0).path("BuySell").asString()).isEqualTo("Sell");
    }

    @Test
    void bracketWithTakeProfitIsUnchanged() {
        // Backwards compatibility: existing callers keep setting the value.
        // Leg order stays TP (index 0) then stop (index 1).
        stubInstrument();
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).willReturn(okJson("""
            {"OrderId":"9001","Orders":[{"OrderId":"9002"},{"OrderId":"9003"}]}
            """)));

        var r = provider.submitBracket(bracketReq());

        assertThat(r.accepted()).isTrue();

        var posts = wm.findAll(postRequestedFor(urlEqualTo("/trade/v2/orders")));
        assertThat(posts).hasSize(1);
        var orders = new tools.jackson.databind.ObjectMapper()
                .readTree(posts.get(0).getBodyAsString()).path("Orders");
        assertThat(orders.size()).isEqualTo(2);
        assertThat(orders.get(0).path("OrderType").asString()).isEqualTo("Limit");
        assertThat(orders.get(0).path("OrderPrice").asString()).isEqualTo("110");
        assertThat(orders.get(1).path("OrderType").asString()).isEqualTo("StopIfTraded");
    }

    @Test
    void submitBracketSellSideFlipsChildBuySell() {
        stubInstrument();
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).willReturn(okJson("""
            {"OrderId":"9001","Orders":[{"OrderId":"9002"},{"OrderId":"9003"}]}
            """)));

        var req = new de.visterion.agora.trading.BracketOrderRequest(
                "AAPL", "sell", new java.math.BigDecimal("1"), "limit", "gtc",
                new java.math.BigDecimal("100"), new java.math.BigDecimal("110"), null,
                new java.math.BigDecimal("90"), "ref-2");

        var r = provider.submitBracket(req);

        assertThat(r.accepted()).isTrue();
        wm.verify(postRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.BuySell", equalTo("Sell")))
                .withRequestBody(matchingJsonPath("$.Orders[0].BuySell", equalTo("Buy")))
                .withRequestBody(matchingJsonPath("$.Orders[1].BuySell", equalTo("Buy"))));
    }

    @Test
    void submitBracketMarketEntryDefaultsToDayOrder() {
        // 🔶 Saxo semantics: a Market entry with no explicit timeInForce defaults to
        // DayOrder, not GoodTillCancel — see class-level flatten() javadoc / H6 discussion.
        stubInstrument();
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).willReturn(okJson("""
            {"OrderId":"9001","Orders":[{"OrderId":"9002"},{"OrderId":"9003"}]}
            """)));

        var req = new de.visterion.agora.trading.BracketOrderRequest(
                "AAPL", "buy", new java.math.BigDecimal("1"), "market", null, null,
                new java.math.BigDecimal("90"), null,
                new java.math.BigDecimal("110"), "ref-market-1");

        var r = provider.submitBracket(req);

        assertThat(r.accepted()).isTrue();
        wm.verify(postRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.OrderType", equalTo("Market")))
                .withRequestBody(matchingJsonPath("$.OrderDuration.DurationType", equalTo("DayOrder"))));
    }

    @Test
    void submitBracketStopLossLimitEmitsStopLimitLeg() {
        stubInstrument();
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).willReturn(okJson("""
            {"OrderId":"9001","Orders":[{"OrderId":"9002"},{"OrderId":"9003"}]}
            """)));

        var req = new de.visterion.agora.trading.BracketOrderRequest(
                "AAPL", "buy", new java.math.BigDecimal("1"), "limit", "gtc",
                new java.math.BigDecimal("100"), new java.math.BigDecimal("90"),
                new java.math.BigDecimal("89.5"),
                new java.math.BigDecimal("110"), "ref-3");

        var r = provider.submitBracket(req);

        assertThat(r.accepted()).isTrue();
        wm.verify(postRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.Orders[1].OrderType", equalTo("StopLimit")))
                .withRequestBody(matchingJsonPath("$.Orders[1].StopLimitPrice", equalTo("89.5"))));
    }

    @Test
    void submitBracketFetchesLegIdsFromRelatedOpenOrders() {
        stubInstrument();
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).willReturn(okJson("""
            {"OrderId":"9001","Orders":[{"OrderId":"9002"},{"OrderId":"9003"}]}
            """)));
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"9001","OpenOrderType":"Limit","Status":"Working","Uic":211,"AssetType":"Stock",
               "BuySell":"Buy","Amount":1.0,"OrderRelation":"IfDoneMaster",
               "RelatedOpenOrders":[
                 {"OrderId":"9002","OpenOrderType":"Limit","Status":"NotWorking"},
                 {"OrderId":"9003","OpenOrderType":"StopIfTraded","Status":"NotWorking"}]}
            ]}
            """)));

        var r = provider.submitBracket(bracketReq());

        assertThat(r.accepted()).isTrue();
        assertThat(r.brokerOrderId()).isEqualTo("9001");
        assertThat(r.takeProfitLegId()).isEqualTo("9002");
        assertThat(r.stopLegId()).isEqualTo("9003");
        // success on first attempt -> no retry, no wasted GET
        wm.verify(1, getRequestedFor(urlPathEqualTo("/port/v1/orders/me")));
    }

    @Test
    void submitBracketRetriesLegLookupUntilLegsAppear() {
        stubInstrument();
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).willReturn(okJson("""
            {"OrderId":"9001","Orders":[{"OrderId":"9002"},{"OrderId":"9003"}]}
            """)));
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).inScenario("leg-lookup")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("{\"Data\":[]}"))
                .willSetStateTo("second"));
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).inScenario("leg-lookup")
                .whenScenarioStateIs("second")
                .willReturn(okJson("""
                    {"Data":[
                      {"OrderId":"9001","OpenOrderType":"Limit","Status":"Working","Uic":211,"AssetType":"Stock",
                       "BuySell":"Buy","Amount":1.0,"OrderRelation":"IfDoneMaster",
                       "RelatedOpenOrders":[
                         {"OrderId":"9002","OpenOrderType":"Limit","Status":"NotWorking"},
                         {"OrderId":"9003","OpenOrderType":"StopIfTraded","Status":"NotWorking"}]}
                    ]}
                    """)));

        var r = provider.submitBracket(bracketReq());

        assertThat(r.accepted()).isTrue();
        assertThat(r.stopLegId()).isEqualTo("9003");
        assertThat(r.takeProfitLegId()).isEqualTo("9002");
        wm.verify(2, getRequestedFor(urlPathEqualTo("/port/v1/orders/me")));
    }

    @Test
    void submitBracketLegLookupFailureStillReportsAccepted() {
        stubInstrument();
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).willReturn(okJson("""
            {"OrderId":"9001","Orders":[{"OrderId":"9002"},{"OrderId":"9003"}]}
            """)));
        // /port/v1/orders/me not stubbed -> WireMock 404s; leg lookup must be best-effort
        var r = provider.submitBracket(bracketReq());

        assertThat(r.accepted()).isTrue();
        assertThat(r.brokerOrderId()).isEqualTo("9001");
        assertThat(r.stopLegId()).isNull();
        assertThat(r.takeProfitLegId()).isNull();
    }

    @Test
    void submitBracketLegLookupRetryIsCappedWhenLegsNeverAppear() {
        stubInstrument();
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).willReturn(okJson("""
            {"OrderId":"9001","Orders":[{"OrderId":"9002"},{"OrderId":"9003"}]}
            """)));
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("{\"Data\":[]}")));

        var r = provider.submitBracket(bracketReq());

        assertThat(r.accepted()).isTrue();
        assertThat(r.stopLegId()).isNull();
        assertThat(r.takeProfitLegId()).isNull();
        wm.verify(3, getRequestedFor(urlPathEqualTo("/port/v1/orders/me")));
    }

    @Test
    void submitBracketUnknownSymbolIsRejectedNotUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/ref/v1/instruments")).willReturn(okJson("{\"Data\":[]}")));
        var r = provider.submitBracket(bracketReq());
        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectReason()).contains("unknown symbol");
        wm.verify(0, postRequestedFor(urlEqualTo("/trade/v2/orders")));
    }

    @Test
    void submitBracket400MapsErrorInfoToRejected() {
        stubInstrument();
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"ErrorInfo":{"ErrorCode":"IllegalInstrumentId","Message":"Instrument not tradable"}}
                    """)));
        var r = provider.submitBracket(bracketReq());
        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectReason()).isEqualTo("Instrument not tradable");
        assertThat(r.rejectCode()).isEqualTo("IllegalInstrumentId");
    }

    @Test
    void submitBracket409IsUnavailable() {
        stubInstrument();
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).willReturn(aResponse().withStatus(409)));
        assertThatThrownBy(() -> provider.submitBracket(bracketReq()))
                .isInstanceOf(BrokerException.class)
                .hasMessageContaining("duplicate");
    }

    // ---- provider-call interceptor dedup: bespoke body logs must not duplicate it ----

    @Test
    void submitBracketSuccessDoesNotLogBespokeBodyLine() {
        stubInstrument();
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).willReturn(okJson("""
            {"OrderId":"9001","Orders":[{"OrderId":"9002"},{"OrderId":"9003"}]}
            """)));

        Logger logger = (Logger) LoggerFactory.getLogger(SaxoBrokerProvider.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            var r = provider.submitBracket(bracketReq());
            assertThat(r.accepted()).isTrue();
            // The provider-call interceptor (agora.providercall) now logs this response
            // uniformly — SaxoBrokerProvider must not also emit its own success body line.
            assertThat(appender.list).noneMatch(e ->
                    e.getFormattedMessage().contains("saxo response [POST /trade/v2/orders (bracket)]: status=success"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void submitBracketRejectLogsRedactedBodyViaWriteError() {
        stubInstrument();
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"ErrorInfo":{"ErrorCode":"IllegalInstrumentId","Message":"Instrument not tradable"},"token":"SEKRET123"}
                    """)));

        Logger logger = (Logger) LoggerFactory.getLogger(SaxoBrokerProvider.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            var r = provider.submitBracket(bracketReq());
            assertThat(r.accepted()).isFalse();
            var writeErrorLines = appender.list.stream()
                    .filter(e -> e.getFormattedMessage().contains("saxo response [POST /trade/v2/orders (bracket)]: status=400"))
                    .toList();
            assertThat(writeErrorLines).hasSize(1);
            String line = writeErrorLines.get(0).getFormattedMessage();
            assertThat(line).doesNotContain("SEKRET123");
            assertThat(line).contains("\"token\":\"***\"");
        } finally {
            logger.detachAppender(appender);
        }
    }

    // ---- submitBracket far-stop fallback (REACTIVE: real bracket 400 TooFarFromEntryOrder) ----

    /** The atomic 400/writeError path: nothing was placed by this rejected POST — confirmed
     *  by the fact that the fallback then places a genuinely NEW entry via a second POST. */
    private void stubBracketRejectTooFar(String scenario) {
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {"ErrorInfo":{"ErrorCode":"TooFarFromEntryOrder","Message":"Order price is too far from the entry order"}}
                            """))
                .willSetStateTo("toofar-rejected"));
    }

    @Test
    void submitBracketOtherRejectionIsRejectedWithoutFallback() {
        stubInstrument();
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"ErrorInfo":{"ErrorCode":"IllegalInstrumentId","Message":"Instrument not tradable"}}
                    """)));

        var r = provider.submitBracket(bracketReq());

        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectReason()).isEqualTo("Instrument not tradable");
        assertThat(r.rejectCode()).isEqualTo("IllegalInstrumentId");
        // no fallback triggered — exactly the one rejected POST, nothing further
        wm.verify(1, postRequestedFor(urlEqualTo("/trade/v2/orders")));
    }

    @Test
    void submitBracketTooFarRejectTriggersReactiveFallback() {
        stubInstrument();
        stubBracketRejectTooFar("far-stop-reactive-ok");
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("far-stop-reactive-ok")
                .whenScenarioStateIs("toofar-rejected")
                .willReturn(okJson("{\"OrderId\":\"E1\"}"))
                .willSetStateTo("entry-placed"));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("far-stop-reactive-ok")
                .whenScenarioStateIs("entry-placed")
                .willReturn(okJson("{\"OrderId\":\"S1\"}")));

        var r = provider.submitBracket(bracketReq());

        assertThat(r.accepted()).isTrue();
        assertThat(r.brokerOrderId()).isEqualTo("E1");
        assertThat(r.clientRef()).isEqualTo("ref-1");
        assertThat(r.stopLegId()).isEqualTo("S1");
        assertThat(r.takeProfitLegId()).isNull();

        var posts = wm.findAll(postRequestedFor(urlEqualTo("/trade/v2/orders")));
        assertThat(posts).hasSize(3);
        // 1st POST: full bracket body (rejected TooFar)
        assertThat(posts.get(0).getBodyAsString()).contains("Orders");
        // 2nd POST: entry-only re-placement
        assertThat(posts.get(1).getBodyAsString()).doesNotContain("Orders");
        // 3rd POST: standalone stop
        assertThat(posts.get(2).getBodyAsString()).doesNotContain("Orders");
        String entryReqId = posts.get(1).getHeader("X-Request-ID");
        String stopReqId = posts.get(2).getHeader("X-Request-ID");
        assertThat(entryReqId).isNotNull();
        // Fallback entry gets a FRESH X-Request-ID, distinct from the clientRef the rejected
        // bracket already consumed under that dedupe key — otherwise Saxo could replay/409
        // the rejected bracket's cached response instead of placing the entry.
        assertThat(entryReqId).isNotEqualTo("ref-1");
        assertThat(stopReqId).isNotNull().isNotEqualTo(entryReqId);
        // ExternalReference on the entry body still carries the clientRef for order
        // tracking / orderByClientRef reconcile matching — only the header changed.
        assertThat(posts.get(1).getBodyAsString()).contains("\"ExternalReference\":\"ref-1\"");

        wm.verify(postRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.OrderType", equalTo("StopIfTraded")))
                .withRequestBody(matchingJsonPath("$.BuySell", equalTo("Sell")))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("1")))
                .withRequestBody(matchingJsonPath("$.OrderPrice", equalTo("90")))
                .withRequestBody(matchingJsonPath("$.OrderDuration.DurationType", equalTo("GoodTillCancel")))
                .withRequestBody(matchingJsonPath("$.Uic", equalTo("211"))));
    }

    @Test
    void submitBracketFarStopFallbackFailSafeCancelsEntryWhenStopPlacementFails() {
        stubInstrument();
        stubBracketRejectTooFar("far-stop-cancel");
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("far-stop-cancel")
                .whenScenarioStateIs("toofar-rejected")
                .willReturn(okJson("{\"OrderId\":\"E1\"}"))
                .willSetStateTo("entry-placed"));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("far-stop-cancel")
                .whenScenarioStateIs("entry-placed")
                .willReturn(aResponse().withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {"ErrorInfo":{"ErrorCode":"SomeStopRejection","Message":"stop rejected"}}
                            """)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/E1")).willReturn(aResponse().withStatus(200)));
        // entry was purely unfilled: cancel (200) removed the working order, so the
        // fail-safe's always-on flatten finds no residual position — NOT_FOUND is tolerated.
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("{\"Data\":[]}")));

        var r = provider.submitBracket(bracketReq());

        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectCode()).isEqualTo("STOP_PLACEMENT_FAILED");
        wm.verify(deleteRequestedFor(urlPathEqualTo("/trade/v2/orders/E1"))
                .withQueryParam("AccountKey", equalTo("Acc+Key/1==")));
        wm.verify(getRequestedFor(urlPathEqualTo("/port/v1/netpositions")));
        // no position existed (pure unfilled) — flatten's NOT_FOUND is tolerated without a
        // fourth order POST (only the rejected bracket + the entry + the failed stop were placed above)
        wm.verify(3, postRequestedFor(urlEqualTo("/trade/v2/orders")));
    }

    @Test
    void submitBracketFarStopFallbackFlattensPartialFillAfterSuccessfulCancel() {
        stubInstrument();
        stubBracketRejectTooFar("far-stop-partial-fill");
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("far-stop-partial-fill")
                .whenScenarioStateIs("toofar-rejected")
                .willReturn(okJson("{\"OrderId\":\"E1\"}"))
                .willSetStateTo("entry-placed"));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("far-stop-partial-fill")
                .whenScenarioStateIs("entry-placed")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("stop-failed"));
        // flatten's own closing Market order for the residual position left by the partial fill
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("far-stop-partial-fill")
                .whenScenarioStateIs("stop-failed")
                .willReturn(okJson("{\"OrderId\":\"F1\"}")));
        // cancel succeeds (200) — Saxo cancels only the still-working remainder, but a partial
        // fill still leaves a live, unprotected position behind that cancel alone cannot see.
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/E1")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":1.0,"Uic":211,"AssetType":"Stock"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("{\"Data\":[]}")));

        var r = provider.submitBracket(bracketReq());

        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectCode()).isEqualTo("STOP_PLACEMENT_FAILED");
        wm.verify(deleteRequestedFor(urlPathEqualTo("/trade/v2/orders/E1")));
        // this is the previously-uncovered gap: flatten must run even though cancel succeeded
        wm.verify(getRequestedFor(urlPathEqualTo("/port/v1/netpositions")));
        wm.verify(postRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.OrderType", equalTo("Market")))
                .withRequestBody(matchingJsonPath("$.BuySell", equalTo("Sell"))));
    }

    @Test
    void submitBracketFarStopFallbackFlattensWhenEntryAlreadyFilledBeforeCancel() {
        stubInstrument();
        stubBracketRejectTooFar("far-stop-flatten");
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("far-stop-flatten")
                .whenScenarioStateIs("toofar-rejected")
                .willReturn(okJson("{\"OrderId\":\"E1\"}"))
                .willSetStateTo("entry-placed"));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("far-stop-flatten")
                .whenScenarioStateIs("entry-placed")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("stop-failed"));
        // flatten's own closing Market order, once the fail-safe kicks in
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("far-stop-flatten")
                .whenScenarioStateIs("stop-failed")
                .willReturn(okJson("{\"OrderId\":\"F1\"}")));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/E1")).willReturn(aResponse().withStatus(404)));
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":1.0,"Uic":211,"AssetType":"Stock"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("{\"Data\":[]}")));

        var r = provider.submitBracket(bracketReq());

        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectCode()).isEqualTo("STOP_PLACEMENT_FAILED");
        wm.verify(deleteRequestedFor(urlPathEqualTo("/trade/v2/orders/E1")));
        wm.verify(getRequestedFor(urlPathEqualTo("/port/v1/netpositions")));
        wm.verify(postRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.OrderType", equalTo("Market")))
                .withRequestBody(matchingJsonPath("$.BuySell", equalTo("Sell"))));
    }

    @Test
    void submitBracketFarStopFallbackFlattensWhenCancelFailsWithNonNotFoundError() {
        stubInstrument();
        stubBracketRejectTooFar("far-stop-cancel-unavailable");
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("far-stop-cancel-unavailable")
                .whenScenarioStateIs("toofar-rejected")
                .willReturn(okJson("{\"OrderId\":\"E1\"}"))
                .willSetStateTo("entry-placed"));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("far-stop-cancel-unavailable")
                .whenScenarioStateIs("entry-placed")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("stop-failed"));
        // flatten's own closing Market order, once the last-resort fail-safe kicks in
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("far-stop-cancel-unavailable")
                .whenScenarioStateIs("stop-failed")
                .willReturn(okJson("{\"OrderId\":\"F1\"}")));
        // cancel fails with a non-404 error (5xx/timeout) — state is ambiguous, entry may
        // already be filled, so the fail-safe must fall through to flatten rather than give up.
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/E1")).willReturn(aResponse().withStatus(500)));
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":1.0,"Uic":211,"AssetType":"Stock"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("{\"Data\":[]}")));

        var r = provider.submitBracket(bracketReq());

        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectCode()).isEqualTo("STOP_PLACEMENT_FAILED");
        wm.verify(deleteRequestedFor(urlPathEqualTo("/trade/v2/orders/E1")));
        wm.verify(getRequestedFor(urlPathEqualTo("/port/v1/netpositions")));
        wm.verify(postRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.OrderType", equalTo("Market")))
                .withRequestBody(matchingJsonPath("$.BuySell", equalTo("Sell"))));
    }

    // ---- X-Request-ID is a per-attempt key, not a business key ----

    @Test
    void everyAttemptGetsAFreshRequestIdWhileExternalReferenceStaysTheClientRef() {
        // Dracul passes a DETERMINISTIC clientRef. If that also became the X-Request-ID, the
        // second attempt would hit Saxo's dedupe cache (observed 2026-07-25: 400 → retry → 409)
        // and the key would be burned for good. The stable business key is ExternalReference.
        stubInstrument();
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).willReturn(okJson("{\"OrderId\":\"9001\"}")));
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("{\"Data\":[]}")));

        provider.submitBracket(bracketReq());
        provider.submitBracket(bracketReq());

        var posts = wm.findAll(postRequestedFor(urlEqualTo("/trade/v2/orders")));
        assertThat(posts).hasSize(2);
        String first = posts.get(0).getHeader("X-Request-ID");
        String second = posts.get(1).getHeader("X-Request-ID");
        assertThat(first).isNotNull().isNotEqualTo("ref-1");
        assertThat(second).isNotNull().isNotEqualTo("ref-1").isNotEqualTo(first);
        assertThat(posts.get(0).getBodyAsString()).contains("\"ExternalReference\":\"ref-1\"");
        assertThat(posts.get(1).getBodyAsString()).contains("\"ExternalReference\":\"ref-1\"");
    }

    @Test
    void farStopFallbackWaitsBeforeRePlacingTheEntry() {
        // The fallback used to fire ~90 ms after the rejected bracket and tripped Saxo's own
        // order rate limit every single time (0 of 5 got through in the 14 days before
        // 2026-07-25). The delay seam mirrors legLookupDelayMillis: a package-private field
        // that tests neutralize — here the sleep is observed instead of actually slept.
        stubInstrument();
        stubBracketRejectTooFar("far-stop-delay");
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("far-stop-delay")
                .whenScenarioStateIs("toofar-rejected")
                .willReturn(okJson("{\"OrderId\":\"E1\"}"))
                .willSetStateTo("entry-placed"));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("far-stop-delay")
                .whenScenarioStateIs("entry-placed")
                .willReturn(okJson("{\"OrderId\":\"S1\"}")));

        var sleeps = new java.util.concurrent.atomic.AtomicInteger();
        var postsSeenAtSleep = new java.util.concurrent.atomic.AtomicInteger(-1);
        var spy = new SaxoBrokerProvider(cfg, store,
                RestClient.builder().baseUrl(wm.baseUrl()).build(), resolver()) {
            @Override void sleepBeforeFarStopFallback() {
                sleeps.incrementAndGet();
                postsSeenAtSleep.set(wm.findAll(postRequestedFor(urlEqualTo("/trade/v2/orders"))).size());
            }
        };
        spy.legLookupDelayMillis = 0;

        var r = spy.submitBracket(bracketReq());

        assertThat(r.accepted()).isTrue();
        assertThat(sleeps.get()).isEqualTo(1);
        // exactly one POST had happened when the wait started: the rejected bracket. The
        // fallback entry comes AFTER the wait.
        assertThat(postsSeenAtSleep.get()).isEqualTo(1);
        // the wait is the configured one, defaulting to the documented constant
        assertThat(spy.farStopDelayMillis).isEqualTo(SaxoBrokerProvider.FAR_STOP_DELAY_MS);
    }

    // ---- rejectedLeg: which leg did Saxo actually reject? ----

    /** Echter 400-Body aus dem Vorfall vom 2026-07-25 (Run 8BA7038B…, Symbol STT). */
    private static final String REAL_REJECT_BODY = """
        {"ErrorInfo":{"ErrorCode":"TooFarFromEntryOrder","Message":"Order-Preis ist zu weit von der Eingabeorder entfernt."},
         "ExternalReference":"t2-5016b4d3-db04-42af-b1dc-8399455d618c",
         "Orders":[{"ErrorInfo":{"ErrorCode":"TooFarFromEntryOrder","Message":"Order-Preis ist zu weit von der Eingabeorder entfernt."}},
                   {"ErrorInfo":{"ErrorCode":"OrderNotPlaced","Message":"Order not placed as other order in request was rejected."}}]}
        """;

    @Test
    void rejectedLegNamesTakeProfitOnTheRealIncidentBody() {
        var body = SaxoBrokerProvider.MAPPER.readTree(REAL_REJECT_BODY);
        assertThat(SaxoBrokerProvider.rejectedLeg(body, true)).isEqualTo("take_profit");
    }

    @Test
    void rejectedLegSkipsOrderNotPlacedCollateralAndNamesTheStop() {
        // Mirror image of the incident: leg 0 is the collateral damage, leg 1 the real cause.
        var body = SaxoBrokerProvider.MAPPER.readTree("""
            {"ErrorInfo":{"ErrorCode":"TooFarFromEntryOrder","Message":"too far"},
             "Orders":[{"ErrorInfo":{"ErrorCode":"OrderNotPlaced","Message":"Order not placed as other order in request was rejected."}},
                       {"ErrorInfo":{"ErrorCode":"TooFarFromEntryOrder","Message":"too far"}}]}
            """);
        assertThat(SaxoBrokerProvider.rejectedLeg(body, true)).isEqualTo("stop_loss");
    }

    @Test
    void rejectedLegWithoutTakeProfitAlwaysNamesTheStop() {
        // No take-profit was sent, so index 0 IS the stop (see submitBracket's leg-order note).
        var body = SaxoBrokerProvider.MAPPER.readTree("""
            {"ErrorInfo":{"ErrorCode":"TooFarFromEntryOrder","Message":"too far"},
             "Orders":[{"ErrorInfo":{"ErrorCode":"TooFarFromEntryOrder","Message":"too far"}}]}
            """);
        assertThat(SaxoBrokerProvider.rejectedLeg(body, false)).isEqualTo("stop_loss");
    }

    @Test
    void rejectedLegIsNullWhenTheBodyCarriesNoPerLegInfo() {
        var body = SaxoBrokerProvider.MAPPER.readTree("""
            {"ErrorInfo":{"ErrorCode":"TooFarFromEntryOrder","Message":"too far"}}
            """);
        assertThat(SaxoBrokerProvider.rejectedLeg(body, true)).isNull();
    }

    @Test
    void fallbackFailureCarriesBothCauses() {
        // Bracket wird mit 400 TooFarFromEntryOrder abgelehnt, der Fallback-Entry dann
        // mit 429 — exakt der Ablauf vom 2026-07-25. Der Aufrufer muss BEIDE Ursachen
        // sehen; bisher gewann die zweite und die erste ging verloren.
        stubInstrument();
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("far-stop-429")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody(REAL_REJECT_BODY))
                .willSetStateTo("toofar-rejected"));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("far-stop-429")
                .whenScenarioStateIs("toofar-rejected")
                .willReturn(aResponse().withStatus(429)));

        // One call only — the WireMock scenario advances, so re-invoking would hit a
        // different stub.
        Throwable t = catchThrowable(() -> provider.submitBracket(bracketReq()));

        assertThat(t).isInstanceOf(BrokerException.class);
        // the 429 mapping (NOT_READY, "retry shortly") must survive unchanged …
        assertThat(((BrokerException) t).kind()).isEqualTo(BrokerException.Kind.NOT_READY);
        // … while the message now names BOTH causes: what the bracket was rejected for
        // (and at which leg), and what the fallback then failed with.
        assertThat(t).hasMessageContaining("TooFarFromEntryOrder")
                .hasMessageContaining("take_profit")
                .hasMessageContaining("rate limited");
    }

    // ---- cancel ----

    @Test
    void cancelDeletesWithAccountKey() {
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/9001"))
                .willReturn(aResponse().withStatus(200)));
        var r = provider.cancel("9001");
        assertThat(r.accepted()).isTrue();
        assertThat(r.status()).isEqualTo("canceled");
        wm.verify(deleteRequestedFor(urlPathEqualTo("/trade/v2/orders/9001"))
                .withQueryParam("AccountKey", equalTo("Acc+Key/1==")));
    }

    @Test
    void cancel404IsNotFound() {
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/9001"))
                .willReturn(aResponse().withStatus(404)));
        assertThatThrownBy(() -> provider.cancel("9001"))
                .isInstanceOf(BrokerException.class)
                .extracting(e -> ((BrokerException) e).kind())
                .isEqualTo(BrokerException.Kind.NOT_FOUND);
    }

    // ---- flatten ----

    @Test
    void flattenSendsOppositeMarketOrder() {
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":10.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":150.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders"))
                .willReturn(okJson("{\"OrderId\":\"9100\"}")));

        var r = provider.flatten("AAPL", null, null);

        assertThat(r.accepted()).isTrue();
        assertThat(r.closedQty()).isEqualByComparingTo("10.0");
        assertThat(r.remainingQty()).isEqualByComparingTo("0");
        wm.verify(postRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.BuySell", equalTo("Sell")))
                .withRequestBody(matchingJsonPath("$.OrderType", equalTo("Market")))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("10.0"))));
    }

    @Test
    void flattenWithFraction_sendsPartialAmount() {
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":10.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":150.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders"))
                .willReturn(okJson("{\"OrderId\":\"9100\"}")));

        var r = provider.flatten("AAPL", new java.math.BigDecimal("0.3"), null);

        assertThat(r.accepted()).isTrue();
        assertThat(r.closedQty()).isEqualByComparingTo("3");
        assertThat(r.remainingQty()).isEqualByComparingTo("7");
        wm.verify(postRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("3"))));
    }

    @Test
    void flattenWithQty_sendsExactAmount() {
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":10.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":150.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders"))
                .willReturn(okJson("{\"OrderId\":\"9100\"}")));

        var r = provider.flatten("AAPL", null, new java.math.BigDecimal("4"));

        assertThat(r.accepted()).isTrue();
        assertThat(r.closedQty()).isEqualByComparingTo("4");
        assertThat(r.remainingQty()).isEqualByComparingTo("6");
        wm.verify(postRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("4"))));
    }

    @Test
    void flattenWithQtyExceedingPosition_isRejectedWithoutBrokerCall() {
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":10.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":150.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));

        var r = provider.flatten("AAPL", null, new java.math.BigDecimal("20"));

        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectCode()).isEqualTo("QTY_EXCEEDS_POSITION");
        wm.verify(0, postRequestedFor(urlEqualTo("/trade/v2/orders")));
    }

    @Test
    void flattenWithFractionTruncatingToZero_isRejected() {
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":1.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":150.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));

        var r = provider.flatten("AAPL", new java.math.BigDecimal("0.1"), null);

        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectCode()).isEqualTo("QTY_ROUNDED_TO_ZERO");
        wm.verify(0, postRequestedFor(urlEqualTo("/trade/v2/orders")));
    }

    @Test
    void flattenWithoutPositionIsNotFound() {
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions"))
                .willReturn(okJson("{\"Data\":[]}")));
        assertThatThrownBy(() -> provider.flatten("AAPL", null, null))
                .isInstanceOf(BrokerException.class)
                .extracting(e -> ((BrokerException) e).kind())
                .isEqualTo(BrokerException.Kind.NOT_FOUND);
    }

    @Test
    void flattenSendsXRequestIdHeader() {
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":10.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":150.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders"))
                .willReturn(okJson("{\"OrderId\":\"9100\"}")));

        provider.flatten("AAPL", null, null);

        wm.verify(postRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withHeader("X-Request-ID", matching(".+")));
    }

    @Test
    void flatten409IsUnavailableReplayHint() {
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":10.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":150.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders"))
                .willReturn(aResponse().withStatus(409)));

        assertThatThrownBy(() -> provider.flatten("AAPL", null, null))
                .isInstanceOfSatisfying(BrokerException.class,
                        e -> assertThat(e.kind()).isEqualTo(BrokerException.Kind.UNAVAILABLE));
    }

    @Test
    void flattenInvalidFractionIsRejectedWithoutAnyCall() {
        var r = provider.flatten("AAPL", new java.math.BigDecimal("1.5"), null);
        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectCode()).isEqualTo("INVALID_FRACTION");
        wm.verify(0, getRequestedFor(urlPathEqualTo("/port/v1/netpositions")));
        wm.verify(0, postRequestedFor(urlEqualTo("/trade/v2/orders")));
    }

    @Test
    void flattenZeroFractionIsRejected() {
        var r = provider.flatten("AAPL", new java.math.BigDecimal("0"), null);
        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectCode()).isEqualTo("INVALID_FRACTION");
    }

    // ---- H6: flatten cancels detached protective legs ----

    @Test
    void flattenCancelsRelatedOpenOcoLegsBeforeClosing() {
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":10.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":150.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        // Two detached post-fill Oco legs (Stop + Limit) sharing the position's Uic — no
        // parent survives post-fill, so they're top-level orders (see modifyBySymbolFallback).
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"leg-sl","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell","Amount":10.0},
              {"OrderId":"leg-tp","Uic":211,"OpenOrderType":"Limit","BuySell":"Sell","Amount":10.0}]}
            """)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-sl")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-tp")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).willReturn(okJson("{\"OrderId\":\"9100\"}")));

        var r = provider.flatten("AAPL", null, null);

        assertThat(r.accepted()).isTrue();
        wm.verify(deleteRequestedFor(urlPathEqualTo("/trade/v2/orders/leg-sl")));
        wm.verify(deleteRequestedFor(urlPathEqualTo("/trade/v2/orders/leg-tp")));
        wm.verify(postRequestedFor(urlEqualTo("/trade/v2/orders")));
    }

    @Test
    void flattenCancelsOnlyOppositeSideProtectiveLegsNotSameSideOrders() {
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":10.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":150.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        // The long (Amount 10.0 > 0) position's protective legs are Sell (opposite). A
        // same-side resting Buy Limit (e.g. "add to position") and a same-side Buy
        // StopIfTraded (e.g. a stop-entry for a new position) on the same Uic are unrelated
        // orders and must survive the flatten untouched.
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"leg-sl","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell","Amount":10.0},
              {"OrderId":"leg-tp","Uic":211,"OpenOrderType":"Limit","BuySell":"Sell","Amount":10.0},
              {"OrderId":"same-side-limit","Uic":211,"OpenOrderType":"Limit","BuySell":"Buy","Amount":5.0},
              {"OrderId":"same-side-stop","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Buy","Amount":5.0}]}
            """)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-sl")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-tp")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).willReturn(okJson("{\"OrderId\":\"9100\"}")));

        var r = provider.flatten("AAPL", null, null);

        assertThat(r.accepted()).isTrue();
        wm.verify(deleteRequestedFor(urlPathEqualTo("/trade/v2/orders/leg-sl")));
        wm.verify(deleteRequestedFor(urlPathEqualTo("/trade/v2/orders/leg-tp")));
        wm.verify(0, deleteRequestedFor(urlPathEqualTo("/trade/v2/orders/same-side-limit")));
        wm.verify(0, deleteRequestedFor(urlPathEqualTo("/trade/v2/orders/same-side-stop")));
    }

    @Test
    void flattenLegLookupFailureStillClosesWithWarning() {
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":10.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":150.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(aResponse().withStatus(503)));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).willReturn(okJson("{\"OrderId\":\"9100\"}")));

        var r = provider.flatten("AAPL", null, null);

        assertThat(r.accepted()).isTrue();
        assertThat(r.status()).containsIgnoringCase("warning");
        wm.verify(postRequestedFor(urlEqualTo("/trade/v2/orders")));
    }

    // ---- M-T6: idempotent flatten ----

    @Test
    void flattenRejectsWhenOppositeMarketCloseAlreadyWorking() {
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":10.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":150.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        // A prior flatten's Market Sell order is still Working (e.g. the earlier HTTP
        // response was lost to the caller, but the broker accepted it) — a retry must not
        // stack a second close.
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[{"OrderId":"prior-close","Uic":211,"OpenOrderType":"Market","BuySell":"Sell","Amount":10.0}]}
            """)));

        var r = provider.flatten("AAPL", null, null);

        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectCode()).isEqualTo("CLOSE_ALREADY_PENDING");
        wm.verify(0, postRequestedFor(urlEqualTo("/trade/v2/orders")));
    }

    @Test
    void flattenProceedsWhenPendingOppositeMarketIsSmallerThanRequestedClose() {
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":10.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":150.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        // 3 of the 10-share position already have a Market Sell working. Requesting a full
        // flatten (target: close all 10) must place a NEW order for only the remaining 7 —
        // not a full 10, which would stack to 13 units of sell interest against a 10-share
        // position (oversell / unintended short once both fill).
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[{"OrderId":"prior-partial-close","Uic":211,"OpenOrderType":"Market","BuySell":"Sell","Amount":3.0}]}
            """)));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).willReturn(okJson("{\"OrderId\":\"9100\"}")));

        var r = provider.flatten("AAPL", null, null);

        assertThat(r.accepted()).isTrue();
        assertThat(r.closedQty()).isEqualByComparingTo("7.0");
        wm.verify(postRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("7.0"))));
    }

    @Test
    void flattenNotBlockedByDetachedProtectiveLegsOnPendingCheck() {
        // Protective (Stop/Limit) legs are opposite-side by construction — they must NOT be
        // mistaken for a pending "close already working" (that would make every flatten with
        // a live bracket permanently un-closeable). Only Market-type opposite-side orders
        // count toward the pending-close check.
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":10.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":150.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"leg-sl","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell","Amount":10.0},
              {"OrderId":"leg-tp","Uic":211,"OpenOrderType":"Limit","BuySell":"Sell","Amount":10.0}]}
            """)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-sl")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-tp")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).willReturn(okJson("{\"OrderId\":\"9100\"}")));

        var r = provider.flatten("AAPL", null, null);

        assertThat(r.accepted()).isTrue();
        wm.verify(postRequestedFor(urlEqualTo("/trade/v2/orders")));
    }

    // ---- S7a: partial flatten restores protective legs ----

    /**
     * All requests WireMock has recorded, oldest first — used to assert request ORDER.
     * {@code WireMockServer.getAllServeEvents()} returns the journal newest-first (see
     * {@code AbstractRequestJournal#getRequestsMatching}, which reverses the same source list
     * to produce oldest-first); this reverses it back rather than sorting by logged timestamp,
     * since two fast successive local calls can tie at millisecond resolution.
     */
    private List<LoggedRequest> requestJournalInOrder() {
        List<LoggedRequest> newestFirst = wm.getAllServeEvents().stream()
                .map(com.github.tomakehurst.wiremock.stubbing.ServeEvent::getRequest)
                .toList();
        List<LoggedRequest> oldestFirst = new java.util.ArrayList<>(newestFirst);
        java.util.Collections.reverse(oldestFirst);
        return oldestFirst;
    }

    /** "METHOD /path" (query string stripped) for each request, in the order it was received. */
    private List<String> requestJournalMethodsAndPaths() {
        return requestJournalInOrder().stream()
                .map(req -> req.getMethod().value() + " " + req.getUrl().split("\\?")[0])
                .toList();
    }

    @Test
    void partialFlattenRestoresBothStopLegsSizedToTheRemainder() {
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":46.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":40.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        // Two detached Sell StopIfTraded legs, 24 and 22 shares, both at 45.49 — flatten(0.5)
        // trims to 23 shares, leaving 23 to still protect.
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"leg-24","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":24.0,"Price":45.49,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}},
              {"OrderId":"leg-22","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":22.0,"Price":45.49,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}}]}
            """)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-24")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-22")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("restore-both")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("{\"OrderId\":\"R24\"}"))
                .willSetStateTo("leg1-restored"));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("restore-both")
                .whenScenarioStateIs("leg1-restored")
                .willReturn(okJson("{\"OrderId\":\"R22\"}"))
                .willSetStateTo("leg2-restored"));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("restore-both")
                .whenScenarioStateIs("leg2-restored")
                .willReturn(okJson("{\"OrderId\":\"9100\"}")));

        var r = provider.flatten("AAPL", new java.math.BigDecimal("0.5"), null);

        assertThat(r.accepted()).isTrue();
        assertThat(r.closedQty()).isEqualByComparingTo("23");
        assertThat(r.remainingQty()).isEqualByComparingTo("23");
        assertThat(r.legsCollapsed()).isFalse();

        assertThat(requestJournalMethodsAndPaths()).containsSubsequence(
                "DELETE /trade/v2/orders/leg-24",
                "DELETE /trade/v2/orders/leg-22",
                "POST /trade/v2/orders",
                "POST /trade/v2/orders",
                "POST /trade/v2/orders");

        var posts = requestJournalInOrder().stream()
                .filter(req -> "POST".equals(req.getMethod().value())).toList();
        assertThat(posts).hasSize(3);

        var restore1 = SaxoBrokerProvider.MAPPER.readTree(posts.get(0).getBodyAsString());
        assertThat(restore1.path("Amount").decimalValue()).isEqualByComparingTo("12");
        assertThat(restore1.path("OrderPrice").decimalValue()).isEqualByComparingTo("45.49");
        assertThat(restore1.path("BuySell").asString()).isEqualTo("Sell");
        assertThat(restore1.path("OrderType").asString()).isEqualTo("StopIfTraded");

        var restore2 = SaxoBrokerProvider.MAPPER.readTree(posts.get(1).getBodyAsString());
        assertThat(restore2.path("Amount").decimalValue()).isEqualByComparingTo("11");
        assertThat(restore2.path("OrderPrice").decimalValue()).isEqualByComparingTo("45.49");

        var close = SaxoBrokerProvider.MAPPER.readTree(posts.get(2).getBodyAsString());
        assertThat(close.path("Amount").decimalValue()).isEqualByComparingTo("23");
        assertThat(close.path("OrderType").asString()).isEqualTo("Market");
    }

    @Test
    void partialFlattenPostsTheRestoredPriceFromPriceNotOrderPrice() {
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":10.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":140.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        // orders/me returns a TOP-LEVEL order, which carries "Price" (never "OrderPrice") —
        // see ProtectiveLeg's javadoc. Reading the wrong field silently yields 0 via bd()'s
        // zero-default; a restored stop at price 0 is exactly the bug this test guards against.
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"leg-1","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":10.0,"Price":145.30,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}}]}
            """)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-1")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("price-not-orderprice")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("{\"OrderId\":\"R1\"}"))
                .willSetStateTo("leg-restored"));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("price-not-orderprice")
                .whenScenarioStateIs("leg-restored")
                .willReturn(okJson("{\"OrderId\":\"9100\"}")));

        var r = provider.flatten("AAPL", new java.math.BigDecimal("0.4"), null);

        assertThat(r.accepted()).isTrue();
        assertThat(r.closedQty()).isEqualByComparingTo("4");
        assertThat(r.remainingQty()).isEqualByComparingTo("6");

        var posts = requestJournalInOrder().stream()
                .filter(req -> "POST".equals(req.getMethod().value())).toList();
        assertThat(posts).hasSize(2);
        var restore = SaxoBrokerProvider.MAPPER.readTree(posts.get(0).getBodyAsString());
        assertThat(restore.path("Amount").decimalValue()).isEqualByComparingTo("6");
        assertThat(restore.path("OrderPrice").decimalValue()).isEqualByComparingTo("145.30");
        assertThat(restore.path("OrderPrice").decimalValue()).isNotEqualByComparingTo("0");
    }

    @Test
    void fullFlattenRestoresNothingAndKeepsTodaysBehaviour() {
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":10.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":150.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"leg-sl","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":10.0,"Price":140.0,"AssetType":"Stock"},
              {"OrderId":"leg-tp","Uic":211,"OpenOrderType":"Limit","BuySell":"Sell",
               "Amount":10.0,"Price":160.0,"AssetType":"Stock"}]}
            """)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-sl")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-tp")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).willReturn(okJson("{\"OrderId\":\"9100\"}")));

        // fraction and qty both absent -> full flatten, nothing to restore.
        var r = provider.flatten("AAPL", null, null);

        assertThat(r.accepted()).isTrue();
        assertThat(r.closedQty()).isEqualByComparingTo("10");
        assertThat(r.remainingQty()).isEqualByComparingTo("0");
        assertThat(r.protectiveLegs()).isEmpty();
        assertThat(r.legsCollapsed()).isFalse();
        // exactly ONE POST — the closing Market order. No restore POSTs at all.
        wm.verify(1, postRequestedFor(urlEqualTo("/trade/v2/orders")));
        wm.verify(postRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.OrderType", equalTo("Market")))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("10.0"))));
    }

    @Test
    void aFailedCancelBlocksThePlacementAndRejects() {
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":20.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":50.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"leg-a","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":12.0,"Price":50.0,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}},
              {"OrderId":"leg-b","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":8.0,"Price":50.0,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}}]}
            """)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-a")).willReturn(aResponse().withStatus(200)));
        // leg B's cancel fails outright.
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-b")).willReturn(aResponse().withStatus(500)));
        // the only POST that may happen is leg A being put back at its FULL original amount (12).
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).willReturn(okJson("{\"OrderId\":\"back-a\"}")));

        var r = provider.flatten("AAPL", new java.math.BigDecimal("0.5"), null);

        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectCode()).isEqualTo("LEG_CANCEL_INCOMPLETE");
        // leg A comes back restored at full size under a NEW id; leg B's cancel itself failed,
        // so it never moved — it is still live at the broker under its ORIGINAL id, and must be
        // named too (T5 finding: a leg whose cancel failed must not vanish from protectiveLegs()).
        assertThat(r.protectiveLegs()).hasSize(2);
        assertThat(r.protectiveLegs()).extracting("replaces", "orderId")
                .containsExactlyInAnyOrder(
                        tuple("leg-a", "back-a"),
                        tuple("leg-b", "leg-b"));
        assertThat(r.protectiveLegs().stream().filter(l -> l.replaces().equals("leg-a")).findFirst()
                .orElseThrow().qty()).isEqualByComparingTo("12");
        assertThat(r.protectiveLegs().stream().filter(l -> l.replaces().equals("leg-b")).findFirst()
                .orElseThrow().qty()).isEqualByComparingTo("8");

        // exactly one POST — leg A restored at FULL size, never the closing Market order.
        wm.verify(1, postRequestedFor(urlEqualTo("/trade/v2/orders")));
        wm.verify(postRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("12.0")))
                .withRequestBody(matchingJsonPath("$.OrderPrice", equalTo("50.0")))
                .withRequestBody(matchingJsonPath("$.OrderType", equalTo("StopIfTraded"))));
    }

    @Test
    void aFailedSizedLegPlacementCancelsTheAlreadyPlacedOrphanBeforeRollingBack() {
        // Regression for fix-round-1 finding 1: placeSizedLegs stops at the first placement
        // failure but had left the earlier successful placement(s) live. Rolling back to full
        // size on top of that, without cancelling the orphan first, would double opposite-side
        // interest against the holding (58 working against a 46-share position, measured).
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":46.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":40.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"leg-24","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":24.0,"Price":45.49,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}},
              {"OrderId":"leg-22","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":22.0,"Price":45.49,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}}]}
            """)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-24")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-22")).willReturn(aResponse().withStatus(200)));
        // First sized restore (Amount 12) succeeds and is now LIVE at the broker as "orphan-12".
        // Second sized restore (Amount 11) fails outright.
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("orphan-cancel")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("{\"OrderId\":\"orphan-12\"}"))
                .willSetStateTo("leg1-placed"));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("orphan-cancel")
                .whenScenarioStateIs("leg1-placed")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("leg2-failed"));
        // The rollback to full size (24, then 22).
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("orphan-cancel")
                .whenScenarioStateIs("leg2-failed")
                .willReturn(okJson("{\"OrderId\":\"back-24\"}"))
                .willSetStateTo("rollback1-placed"));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("orphan-cancel")
                .whenScenarioStateIs("rollback1-placed")
                .willReturn(okJson("{\"OrderId\":\"back-22\"}")));
        // The orphan (Amount-12 leg just placed above) must be cancelled BEFORE the rollback.
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/orphan-12")).willReturn(aResponse().withStatus(200)));

        var r = provider.flatten("AAPL", new java.math.BigDecimal("0.5"), null);

        assertThat(r.accepted()).isFalse();
        // The orphan was cancelled cleanly, so this is a plain LEG_RESTORE_FAILED, not the
        // _UNPROTECTED variant — but only because the orphan-cancel above succeeded.
        assertThat(r.rejectCode()).isEqualTo("LEG_RESTORE_FAILED");
        assertThat(r.protectiveLegs()).hasSize(2);
        assertThat(r.protectiveLegs()).extracting("replaces", "orderId")
                .containsExactlyInAnyOrder(
                        tuple("leg-24", "back-24"),
                        tuple("leg-22", "back-22"));
        assertThat(r.protectiveLegs()).extracting("qty")
                .usingElementComparator((a, b) -> ((java.math.BigDecimal) a).compareTo((java.math.BigDecimal) b))
                .containsExactlyInAnyOrder(new java.math.BigDecimal("24"), new java.math.BigDecimal("22"));

        // The orphan MUST have been cancelled — this is the whole point of the fix.
        wm.verify(1, deleteRequestedFor(urlPathEqualTo("/trade/v2/orders/orphan-12")));
        // No Market close was ever placed.
        wm.verify(0, postRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.OrderType", equalTo("Market"))));
        // Total opposite-side interest actually posted must never exceed the 46-share holding
        // at any point: orphan(12) is cancelled before the rollback(24+22) is placed, so the
        // maximum ever concurrently live is 24+22=46, never 58.
        var posts = requestJournalInOrder().stream()
                .filter(req -> "POST".equals(req.getMethod().value())).toList();
        assertThat(posts).hasSize(4);
        var orphanCancelIndex = requestJournalInOrder().indexOf(
                requestJournalInOrder().stream()
                        .filter(req -> "DELETE".equals(req.getMethod().value())
                                && req.getUrl().split("\\?")[0].equals("/trade/v2/orders/orphan-12"))
                        .findFirst().orElseThrow());
        var rollback1PostIndex = requestJournalInOrder().indexOf(posts.get(2));
        assertThat(orphanCancelIndex).isLessThan(rollback1PostIndex);
    }

    @Test
    void aFailedOrphanCancelStopsTheRollbackImmediatelyAndReportsUnprotected() {
        // If the orphan itself cannot be cancelled, it is live, unaccounted-for protection — it
        // must be surfaced, never silently dropped (fix-round-1 finding 1). Fix round 3 finding
        // 3: this branch is now interleaved the same as its twin (the determinate-close-failure
        // rollback) — the loop stops the INSTANT orphan-12's cancel fails, before leg-22's full
        // size is ever attempted. So the live state stays at just the orphan (12 shares, under
        // leg-24's original 24), never grows to include back-24/back-22 at all. Per-leg
        // classification (fix round 2) then correctly calls this UNDER-protected: leg-24 has 12
        // of its original 24 live, leg-22 has none of its 22 — neither leg has MORE than its
        // own original, so this is never OVERCOMMITTED.
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":46.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":40.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"leg-24","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":24.0,"Price":45.49,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}},
              {"OrderId":"leg-22","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":22.0,"Price":45.49,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}}]}
            """)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-24")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-22")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("orphan-uncancellable")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("{\"OrderId\":\"orphan-12\"}"))
                .willSetStateTo("leg1-placed"));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("orphan-uncancellable")
                .whenScenarioStateIs("leg1-placed")
                .willReturn(aResponse().withStatus(500)));
        // NOTE: no stub for a rollback POST (back-24/back-22) — the loop must stop before ever
        // attempting one, since orphan-12's cancel (below) fails first.
        // The orphan's own cancel fails — it stays live.
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/orphan-12")).willReturn(aResponse().withStatus(500)));

        var r = provider.flatten("AAPL", new java.math.BigDecimal("0.5"), null);

        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectCode()).isEqualTo("LEG_RESTORE_FAILED_UNPROTECTED");
        // Only the uncancellable orphan is live — never back-24/back-22, which were never placed.
        assertThat(r.protectiveLegs()).hasSize(1);
        assertThat(r.protectiveLegs()).extracting("orderId").containsExactly("orphan-12");
        assertThat(r.protectiveLegs().get(0).qty()).isEqualByComparingTo("12");

        wm.verify(0, postRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("24.0"))));
        wm.verify(0, postRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("22.0"))));
    }

    @Test
    void aSustainedFailureOnTheSizedLegPlacementBranchLeavesSomeProtectionWorkingNotZero() {
        // Fix round 3 finding 3: the placeSizedLegs-failure branch used to cancel every orphan
        // first and place every full-size leg after, unconditionally — the exact same
        // "sustained failure strips protection to zero" defect the determinate-close-failure
        // branch was fixed for one round earlier. Three stop legs (20+16+10=46, matching the
        // 46-share holding) sized to a 23-share remainder (10+8+5=23): the first two size
        // placements succeed (R-A=10, R-B=8), the third fails outright — triggering this branch.
        // R-A's cancel then succeeds, but its full-size restore (20) fails SUSTAINED — the loop
        // must stop right there, leaving R-B (8 shares, leg-B's sized replacement) untouched and
        // still live, rather than cancelling it too before finding out the restore won't work.
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":46.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":40.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"leg-A","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":20.0,"Price":45.49,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}},
              {"OrderId":"leg-B","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":16.0,"Price":45.49,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}},
              {"OrderId":"leg-C","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":10.0,"Price":45.49,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}}]}
            """)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-A")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-B")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-C")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/R-A")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("10")))
                .willReturn(okJson("{\"OrderId\":\"R-A\"}")));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("8")))
                .willReturn(okJson("{\"OrderId\":\"R-B\"}")));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("5")))
                .willReturn(aResponse().withStatus(500)));
        // leg-A's full-size restore (20) is a SUSTAINED failure.
        wm.stubFor(post(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("20.0")))
                .willReturn(aResponse().withStatus(500)));

        var r = provider.flatten("AAPL", new java.math.BigDecimal("0.5"), null);

        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectCode()).isEqualTo("LEG_RESTORE_FAILED_UNPROTECTED");
        // NEVER zero: R-B (leg-B's sized replacement, 8 shares) was never touched and stays live.
        assertThat(r.protectiveLegs()).hasSize(1);
        assertThat(r.protectiveLegs().get(0).orderId()).isEqualTo("R-B");
        assertThat(r.protectiveLegs().get(0).qty()).isEqualByComparingTo("8");

        wm.verify(0, deleteRequestedFor(urlPathEqualTo("/trade/v2/orders/R-B")));
        wm.verify(0, postRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("16.0"))));
        wm.verify(0, postRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("10.0"))));
    }

    @Test
    void restoredLegsCarryTheIdTheyReplace() {
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":46.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":40.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"leg-24","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":24.0,"Price":45.49,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}},
              {"OrderId":"leg-22","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":22.0,"Price":45.49,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}}]}
            """)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-24")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-22")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("restore-ids")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("{\"OrderId\":\"new-24\"}"))
                .willSetStateTo("leg1-restored"));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("restore-ids")
                .whenScenarioStateIs("leg1-restored")
                .willReturn(okJson("{\"OrderId\":\"new-22\"}"))
                .willSetStateTo("leg2-restored"));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("restore-ids")
                .whenScenarioStateIs("leg2-restored")
                .willReturn(okJson("{\"OrderId\":\"9100\"}")));

        var r = provider.flatten("AAPL", new java.math.BigDecimal("0.5"), null);

        assertThat(r.accepted()).isTrue();
        assertThat(r.protectiveLegs()).hasSize(2);
        assertThat(r.protectiveLegs()).extracting("replaces", "orderId")
                .containsExactly(
                        tuple("leg-24", "new-24"),
                        tuple("leg-22", "new-22"));
    }

    @Test
    void aLegWithoutAUsablePriceIsTreatedAsAFailedRestore() {
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":10.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":150.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        // Neither Price nor OrderPrice present — ProtectiveLeg.from must refuse this leg, not
        // guess a price of 0. On a PARTIAL close this leg is never even cancelled: cancelling
        // it would create a slice with nothing to put back (rule 2), so the position is left
        // exactly as protected as it started — no DELETE at all — and the trim is rejected.
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"leg-no-price","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":10.0,"AssetType":"Stock"}]}
            """)));

        var r = provider.flatten("AAPL", null, new java.math.BigDecimal("4"));

        assertThat(r.accepted()).isFalse();
        // Nothing was ever cancelled (the leg couldn't be read back, so it was left alone), so
        // there is nothing to put back and no Market close is placed. The original leg is still
        // live and protecting the position — verified by the complete absence of a DELETE call.
        assertThat(r.rejectCode()).isEqualTo("LEG_CANCEL_INCOMPLETE");
        // The leg is untouched but still real, working protection under its original id, so it
        // must still be named in protective_legs — never silently dropped. There is no parsed
        // qty/price to report (that's exactly why the leg couldn't be reconstructed), so both
        // are null rather than fabricated.
        assertThat(r.protectiveLegs()).extracting("replaces", "orderId", "qty", "price")
                .containsExactly(tuple("leg-no-price", "leg-no-price", null, null));
        wm.verify(0, deleteRequestedFor(urlPathEqualTo("/trade/v2/orders/leg-no-price")));
        wm.verify(0, postRequestedFor(urlEqualTo("/trade/v2/orders")));
    }

    @Test
    void unreadablePriceLegAlongsideANormalLegStaysReportedLiveUnderItsOriginalId() {
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":20.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":50.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        // leg-a is a normal, reconstructible leg; leg-b has neither Price nor OrderPrice, so
        // ProtectiveLeg.from refuses it. On this PARTIAL close leg-b is left alone (rule 2:
        // cancelling it would create a slice with nothing to put back) — it must never be
        // cancelled, and it must still be named in protective_legs as still live, exactly like a
        // leg whose cancel call failed outright.
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"leg-a","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":12.0,"Price":50.0,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}},
              {"OrderId":"leg-b","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":8.0,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}}]}
            """)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-a")).willReturn(aResponse().withStatus(200)));
        // the only POST that may happen is leg A being put back at its FULL original amount (12).
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).willReturn(okJson("{\"OrderId\":\"back-a\"}")));

        var r = provider.flatten("AAPL", new java.math.BigDecimal("0.5"), null);

        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectCode()).isEqualTo("LEG_CANCEL_INCOMPLETE");
        assertThat(r.protectiveLegs()).hasSize(2);
        assertThat(r.protectiveLegs()).extracting("replaces", "orderId")
                .containsExactlyInAnyOrder(
                        tuple("leg-a", "back-a"),
                        tuple("leg-b", "leg-b"));
        assertThat(r.protectiveLegs().stream().filter(l -> l.replaces().equals("leg-a")).findFirst()
                .orElseThrow().qty()).isEqualByComparingTo("12");
        assertThat(r.protectiveLegs().stream().filter(l -> l.replaces().equals("leg-b")).findFirst()
                .orElseThrow().qty()).isNull();
        assertThat(r.protectiveLegs().stream().filter(l -> l.replaces().equals("leg-b")).findFirst()
                .orElseThrow().price()).isNull();

        // leg-a is cancelled once; leg-b is never touched at all — verified on the request
        // journal, not just the reject code.
        wm.verify(1, deleteRequestedFor(urlPathEqualTo("/trade/v2/orders/leg-a")));
        wm.verify(0, deleteRequestedFor(urlPathEqualTo("/trade/v2/orders/leg-b")));
        // exactly one POST — leg A restored at FULL size, never the closing Market order.
        wm.verify(1, postRequestedFor(urlEqualTo("/trade/v2/orders")));
    }

    @Test
    void collapseIsReportedOnTheResult() {
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":46.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":40.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        // Two Sell stops; the tighter (higher-price) one is leg-x at 50, the wider is leg-y at 45.
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"leg-x","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":24.0,"Price":50.0,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}},
              {"OrderId":"leg-y","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":22.0,"Price":45.0,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}}]}
            """)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-x")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-y")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("collapse")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("{\"OrderId\":\"R1\"}"))
                .willSetStateTo("leg-restored"));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("collapse")
                .whenScenarioStateIs("leg-restored")
                .willReturn(okJson("{\"OrderId\":\"9100\"}")));

        // qty=45 of 46 -> only 1 share remains to protect, not enough for one per stop leg.
        var r = provider.flatten("AAPL", null, new java.math.BigDecimal("45"));

        assertThat(r.accepted()).isTrue();
        assertThat(r.remainingQty()).isEqualByComparingTo("1");
        assertThat(r.legsCollapsed()).isTrue();
        assertThat(r.protectiveLegs()).hasSize(1);
        assertThat(r.protectiveLegs().get(0).replaces()).isEqualTo("leg-x");
        assertThat(r.protectiveLegs().get(0).qty()).isEqualByComparingTo("1");

        var posts = requestJournalInOrder().stream()
                .filter(req -> "POST".equals(req.getMethod().value())).toList();
        assertThat(posts).hasSize(2);
        var restore = SaxoBrokerProvider.MAPPER.readTree(posts.get(0).getBodyAsString());
        assertThat(restore.path("Amount").decimalValue()).isEqualByComparingTo("1");
    }

    @Test
    void aCancelledLegThatRoundsToZeroSharesIsFlaggedInTheStatusNotSilentlyDropped() {
        // Regression for fix-round-1 finding 3: a cancelled Sell Limit (take-profit) leg sized
        // amount*remaining/available can round DOWN to 0 shares and LegAllocation drops it —
        // restored.size() == alloc.sized().size() stays true (both are "1"), so the existing
        // rollback check never fires. Protection must not vanish without a signal.
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":46.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":40.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        // One Sell stop (protects fully down to 1 remaining share) and one Sell Limit
        // take-profit at amount 10 — 10*1/46 truncates to 0, so the take-profit is dropped.
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"leg-stop","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":24.0,"Price":45.49,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}},
              {"OrderId":"leg-tp","Uic":211,"OpenOrderType":"Limit","BuySell":"Sell",
               "Amount":10.0,"Price":55.0,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}}]}
            """)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-stop")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-tp")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("dropped-limit")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("{\"OrderId\":\"R1\"}"))
                .willSetStateTo("stop-restored"));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("dropped-limit")
                .whenScenarioStateIs("stop-restored")
                .willReturn(okJson("{\"OrderId\":\"9100\"}")));

        // qty=45 of 46 -> only 1 share remains.
        var r = provider.flatten("AAPL", null, new java.math.BigDecimal("45"));

        assertThat(r.accepted()).isTrue();
        assertThat(r.remainingQty()).isEqualByComparingTo("1");
        // Only the stop leg came back — the take-profit is gone and must be visible in status.
        assertThat(r.protectiveLegs()).hasSize(1);
        assertThat(r.protectiveLegs().get(0).replaces()).isEqualTo("leg-stop");
        assertThat(r.status()).containsIgnoringCase("rounded to 0");

        var posts = requestJournalInOrder().stream()
                .filter(req -> "POST".equals(req.getMethod().value())).toList();
        assertThat(posts).hasSize(2);   // one restore (stop only) + the closing Market order
    }

    // ---- T5: rollback only on a determinate close failure ----

    @Test
    void aDeterminateCloseRejectionRestoresFullSizedLegs() {
        // The closing Market POST comes back 400 — Saxo's synchronous parsed reject, so the
        // close was definitely never placed. The sized-down legs (12/11, ids R12/R11) already
        // went out and are LIVE — they must be cancelled BEFORE the ORIGINAL amounts (24/22)
        // are restored, or both sets would end up working simultaneously (12+11+24+22=69 against
        // a 46-share holding). Orphan cancel succeeds here, so the reject is the close's own
        // rejection, not the UNPROTECTED variant.
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":46.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":40.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"leg-24","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":24.0,"Price":45.49,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}},
              {"OrderId":"leg-22","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":22.0,"Price":45.49,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}}]}
            """)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-24")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-22")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/R12")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/R11")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("determinate-400")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("{\"OrderId\":\"R12\"}"))
                .willSetStateTo("sized1"));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("determinate-400")
                .whenScenarioStateIs("sized1")
                .willReturn(okJson("{\"OrderId\":\"R11\"}"))
                .willSetStateTo("sized2"));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("determinate-400")
                .whenScenarioStateIs("sized2")
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody("""
                            {"ErrorInfo":{"ErrorCode":"OrderRejected","Message":"close rejected by broker"}}
                            """))
                .willSetStateTo("close-failed"));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("determinate-400")
                .whenScenarioStateIs("close-failed")
                .willReturn(okJson("{\"OrderId\":\"back-24\"}"))
                .willSetStateTo("rollback1"));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("determinate-400")
                .whenScenarioStateIs("rollback1")
                .willReturn(okJson("{\"OrderId\":\"back-22\"}")));

        var r = provider.flatten("AAPL", new java.math.BigDecimal("0.5"), null);

        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectCode()).isEqualTo("OrderRejected");
        assertThat(r.rejectReason()).isEqualTo("close rejected by broker");
        // Only the full-size legs are reported — the sized orphans (R12/R11) were cancelled
        // cleanly, so they are gone, not live protection the caller needs to reconcile.
        assertThat(r.protectiveLegs()).hasSize(2);
        assertThat(r.protectiveLegs()).extracting("replaces", "orderId")
                .containsExactlyInAnyOrder(
                        tuple("leg-24", "back-24"),
                        tuple("leg-22", "back-22"));

        // Order matters: each sized leg is cancelled immediately before ITS OWN full-size
        // replacement is placed — interleaved per leg, not "cancel both, then place both" — so
        // the concurrently-live total never exceeds the 46-share holding at any point.
        assertThat(requestJournalMethodsAndPaths()).containsSubsequence(
                "DELETE /trade/v2/orders/leg-24",
                "DELETE /trade/v2/orders/leg-22",
                "POST /trade/v2/orders",
                "POST /trade/v2/orders",
                "POST /trade/v2/orders",
                "DELETE /trade/v2/orders/R12",
                "POST /trade/v2/orders",
                "DELETE /trade/v2/orders/R11",
                "POST /trade/v2/orders");

        wm.verify(1, deleteRequestedFor(urlPathEqualTo("/trade/v2/orders/R12")));
        wm.verify(1, deleteRequestedFor(urlPathEqualTo("/trade/v2/orders/R11")));

        var posts = requestJournalInOrder().stream()
                .filter(req -> "POST".equals(req.getMethod().value())).toList();
        assertThat(posts).hasSize(5);
        var rollback1 = SaxoBrokerProvider.MAPPER.readTree(posts.get(3).getBodyAsString());
        assertThat(rollback1.path("Amount").decimalValue()).isEqualByComparingTo("24");
        var rollback2 = SaxoBrokerProvider.MAPPER.readTree(posts.get(4).getBodyAsString());
        assertThat(rollback2.path("Amount").decimalValue()).isEqualByComparingTo("22");
    }

    @Test
    void aSingleLegRollbackCanGenuinelyDropProtectionToZero() {
        // exit-tools.md limitation: interleaving cannot help when there is only ONE original
        // leg to interleave against. Its sized replacement cancels cleanly (freeing it), but the
        // full-size restore then fails — there is no second leg still holding sized protection
        // to fall back on, so live protection is genuinely zero for a moment. Distinct from the
        // two-leg cases above, where the untouched/orphaned second leg always keeps SOME
        // protection live.
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":46.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":40.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"leg-46","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":46.0,"Price":45.49,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}}]}
            """)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-46")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/R23")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("single-leg-unprotected")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("{\"OrderId\":\"R23\"}"))
                .willSetStateTo("sized"));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("single-leg-unprotected")
                .whenScenarioStateIs("sized")
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody("""
                            {"ErrorInfo":{"ErrorCode":"OrderRejected","Message":"close rejected by broker"}}
                            """))
                .willSetStateTo("close-failed"));
        // The full-size (46) restore is a sustained failure — never succeeds.
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("single-leg-unprotected")
                .whenScenarioStateIs("close-failed")
                .willReturn(aResponse().withStatus(429)));

        var r = provider.flatten("AAPL", new java.math.BigDecimal("0.5"), null);

        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectCode()).isEqualTo("LEG_RESTORE_FAILED_UNPROTECTED");
        // Nothing is live: R23 was cancelled cleanly (freeing it) and the full-size replacement
        // never got placed — genuinely zero protective legs, not "empty because nothing needed
        // reporting".
        assertThat(r.protectiveLegs()).isEmpty();

        // The sized (23) leg was placed and then cancelled cleanly before the full-size (46)
        // restore was attempted — confirming the rollback did reach and touch this single leg
        // rather than skipping it.
        wm.verify(1, deleteRequestedFor(urlPathEqualTo("/trade/v2/orders/R23")));
        wm.verify(1, postRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("23")))
                .withRequestBody(matchingJsonPath("$.OrderType", equalTo("StopIfTraded"))));
    }

    @Test
    void aRateLimitedCloseIsDeterminateAndAlsoRestoresFullSizedLegs() {
        // This is the case the first spec draft got wrong: a routine 429 must not leave the
        // position permanently under-protected. Same shape as the 400 test above.
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":46.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":40.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"leg-24","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":24.0,"Price":45.49,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}},
              {"OrderId":"leg-22","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":22.0,"Price":45.49,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}}]}
            """)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-24")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-22")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/R12")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/R11")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("determinate-429")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("{\"OrderId\":\"R12\"}"))
                .willSetStateTo("sized1"));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("determinate-429")
                .whenScenarioStateIs("sized1")
                .willReturn(okJson("{\"OrderId\":\"R11\"}"))
                .willSetStateTo("sized2"));
        // The close order (Amount 23) always gets 429 here — matched on its own amount, NOT
        // advanced by scenario state, so the underlying HTTP client's own automatic retry on
        // 429 (observed elsewhere in this suite) hits the identical stub again rather than
        // accidentally consuming the full-size rollback stubs below.
        wm.stubFor(post(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("23")))
                .willReturn(aResponse().withStatus(429)));
        // The full-size rollback POSTs are matched by their OWN amount (24 / 22), independent
        // of scenario state, for the same reason.
        wm.stubFor(post(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("24.0")))
                .willReturn(okJson("{\"OrderId\":\"back-24\"}")));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("22.0")))
                .willReturn(okJson("{\"OrderId\":\"back-22\"}")));

        var r = provider.flatten("AAPL", new java.math.BigDecimal("0.5"), null);

        assertThat(r.accepted()).isFalse();
        assertThat(r.protectiveLegs()).hasSize(2);
        assertThat(r.protectiveLegs()).extracting("replaces", "orderId")
                .containsExactlyInAnyOrder(
                        tuple("leg-24", "back-24"),
                        tuple("leg-22", "back-22"));

        // Exactly one full-size restore POST per leg — regardless of how many times the close
        // attempt itself was retried at the transport level.
        wm.verify(1, postRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("24.0"))));
        wm.verify(1, postRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("22.0"))));
        // The sized legs (R12/R11) must be cancelled — exactly once each — before the full-size
        // legs are placed, or both sets would be concurrently live against the same position.
        wm.verify(1, deleteRequestedFor(urlPathEqualTo("/trade/v2/orders/R12")));
        wm.verify(1, deleteRequestedFor(urlPathEqualTo("/trade/v2/orders/R11")));
        // Interleaved per leg: R12 cancelled immediately before leg-24 is placed at full size,
        // THEN R11 cancelled immediately before leg-22 is placed — not "cancel both, then place
        // both".
        assertThat(requestJournalMethodsAndPaths()).containsSubsequence(
                "DELETE /trade/v2/orders/leg-24",
                "DELETE /trade/v2/orders/leg-22",
                "DELETE /trade/v2/orders/R12",
                "POST /trade/v2/orders",
                "DELETE /trade/v2/orders/R11",
                "POST /trade/v2/orders");
    }

    @Test
    void aFailedOrphanCancelOnDeterminateCloseFailureReportsUnprotected() {
        // leg-24's pair (cancel R12, place back-24) succeeds. leg-22's cancel (R11) then fails
        // outright — it stays live. The interleaved loop stops THERE: it must NOT go on to place
        // back-22, since R11 (11 shares) is still working for that slice — placing another 22 on
        // top would overcommit (11+24+22=57 against 46 held, the exact hazard fix round 2 exists
        // to prevent). Final live total is back-24(24) + R11(11) = 35, still <= 46 held, so this
        // is under-protected (35 < 46), not overcommitted — LEG_RESTORE_FAILED_UNPROTECTED, with
        // BOTH live ids named so the caller can reconcile.
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":46.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":40.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"leg-24","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":24.0,"Price":45.49,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}},
              {"OrderId":"leg-22","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":22.0,"Price":45.49,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}}]}
            """)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-24")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-22")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/R12")).willReturn(aResponse().withStatus(200)));
        // R11's cancel fails outright — it stays live.
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/R11")).willReturn(aResponse().withStatus(500)));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("orphan-cancel-fails")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("{\"OrderId\":\"R12\"}"))
                .willSetStateTo("sized1"));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("orphan-cancel-fails")
                .whenScenarioStateIs("sized1")
                .willReturn(okJson("{\"OrderId\":\"R11\"}"))
                .willSetStateTo("sized2"));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("orphan-cancel-fails")
                .whenScenarioStateIs("sized2")
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody("""
                            {"ErrorInfo":{"ErrorCode":"OrderRejected","Message":"close rejected by broker"}}
                            """))
                .willSetStateTo("close-failed"));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("orphan-cancel-fails")
                .whenScenarioStateIs("close-failed")
                .willReturn(okJson("{\"OrderId\":\"back-24\"}")));
        // NOTE: no stub for a THIRD rollback POST (back-22) — the loop must stop after R11's
        // cancel fails and never attempt it. If it did, WireMock would fall through to an
        // unmatched-request 404, which would also fail this test's assertions below.

        Logger logger = (Logger) LoggerFactory.getLogger(SaxoBrokerProvider.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        OrderResult r;
        try {
            r = provider.flatten("AAPL", new java.math.BigDecimal("0.5"), null);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectCode()).isEqualTo("LEG_RESTORE_FAILED_UNPROTECTED");
        // Exactly the two live orders: the full-size replacement for leg-24, AND the
        // uncancellable sized orphan for leg-22 (R11) — never silently dropped. NOT back-22,
        // which was never placed.
        assertThat(r.protectiveLegs()).hasSize(2);
        assertThat(r.protectiveLegs()).extracting("orderId")
                .containsExactlyInAnyOrder("back-24", "R11");

        wm.verify(1, deleteRequestedFor(urlPathEqualTo("/trade/v2/orders/R12")));
        wm.verify(1, deleteRequestedFor(urlPathEqualTo("/trade/v2/orders/R11")));
        wm.verify(1, postRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("24.0"))));
        wm.verify(0, postRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("22.0"))));

        assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.ERROR
                && e.getFormattedMessage().contains("AAPL")
                && e.getFormattedMessage().contains("orphan"));
    }

    @Test
    void aSustainedRateLimitOnFullSizeRestoreLeavesSomeProtectionWorkingNotZero() {
        // Fix round 2 finding 1: the ORIGINAL "cancel every sized leg, THEN place every
        // full-size leg" shape had a gap — a rate limit that rejects the close is often still in
        // effect milliseconds later for the restore POSTs too. Measured with that shape: 46
        // held, stops 24+22 -> sized 12+11; close -> 429; BOTH full-size restore POSTs -> 429;
        // journal showed R12 and R11 both cancelled and NOTHING placed back — a 46-share
        // position with ZERO working stops on the single most routine failure status.
        //
        // The interleaved fix must never reach zero: R12 (leg-24's sized replacement) gets
        // cancelled, its full-size restore then fails (429) — the loop stops there WITHOUT ever
        // touching leg-22's pair, so R11 (11 shares) is never cancelled and stays live. (Fix
        // round 3: there is deliberately no provider-level retry on this 429 — Apache
        // HttpClient5's own transport-level auto-retry already covers the transient case in
        // production, and a provider-level retry on top of it was found unsafe: placeLeg's
        // fresh X-Request-ID per call means a retry after Saxo had actually queued the order
        // would place a second full-size leg.)
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":46.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":40.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"leg-24","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":24.0,"Price":45.49,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}},
              {"OrderId":"leg-22","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":22.0,"Price":45.49,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}}]}
            """)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-24")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-22")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/R12")).willReturn(aResponse().withStatus(200)));
        // The sized legs place cleanly...
        wm.stubFor(post(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("12")))
                .willReturn(okJson("{\"OrderId\":\"R12\"}")));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("11")))
                .willReturn(okJson("{\"OrderId\":\"R11\"}")));
        // ...but the close, AND the full-size restore for leg-24, are BOTH sustained 429 — the
        // same rate limit, not lifted.
        wm.stubFor(post(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("23")))
                .willReturn(aResponse().withStatus(429)));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("24.0")))
                .willReturn(aResponse().withStatus(429)));

        var r = provider.flatten("AAPL", new java.math.BigDecimal("0.5"), null);

        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectCode()).isEqualTo("LEG_RESTORE_FAILED_UNPROTECTED");
        // NEVER zero: R11 (leg-22's sized replacement, 11 shares) was never touched by the loop
        // and stays live — this is the whole point of the fix.
        assertThat(r.protectiveLegs()).isNotEmpty();
        assertThat(r.protectiveLegs()).hasSize(1);
        assertThat(r.protectiveLegs().get(0).orderId()).isEqualTo("R11");
        assertThat(r.protectiveLegs().get(0).qty()).isEqualByComparingTo("11");

        // R12 WAS cancelled (its full-size restore was attempted and failed); leg-22's pair was
        // never even reached — R11 must never have been cancelled, and no Amount-22 POST ever sent.
        wm.verify(1, deleteRequestedFor(urlPathEqualTo("/trade/v2/orders/R12")));
        wm.verify(0, deleteRequestedFor(urlPathEqualTo("/trade/v2/orders/R11")));
        wm.verify(0, postRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("22.0"))));
        assertThat(requestJournalMethodsAndPaths()).containsSubsequence(
                "DELETE /trade/v2/orders/leg-24",
                "DELETE /trade/v2/orders/leg-22",
                "DELETE /trade/v2/orders/R12");
    }

    @Test
    void aBracketsStopAndTakeProfitAreClassifiedPerLegNotByCrossLegSum() {
        // Fix round 2 finding 2: submitBracket places the stop-loss AND the take-profit EACH at
        // the full position qty — both opposite-side, both matched by flatten's own protective-
        // leg filter — so a HEALTHY bracketed position always carries 2x the holding in
        // protective interest by design (46+46=92 against 46 held is normal, not a defect). A
        // cross-leg SUM comparison against `available` would therefore call almost any bracket
        // rollback OVERCOMMITTED. Measured (the exact probe that found this): 46 held, sl-46 +
        // tp-46, sized 23+23, close rejected (400), the take-profit's sized replacement (S23b)
        // fails to cancel. Actual live state: back-sl(46, matches its own original exactly) +
        // S23b(23, HALF of its own original 46) — the take-profit is under-restored, not
        // anything over-committed. Per-leg classification must call this UNPROTECTED.
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":46.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":40.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"sl-46","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":46.0,"Price":45.49,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}},
              {"OrderId":"tp-46","Uic":211,"OpenOrderType":"Limit","BuySell":"Sell",
               "Amount":46.0,"Price":55.0,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}}]}
            """)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/sl-46")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/tp-46")).willReturn(aResponse().withStatus(200)));
        // Both sized restores (23 each) place cleanly — discriminated by OrderType, since both
        // share the same Amount (23) as the close order below.
        wm.stubFor(post(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.OrderType", equalTo("StopIfTraded")))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("23")))
                .willReturn(okJson("{\"OrderId\":\"S23a\"}")));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.OrderType", equalTo("Limit")))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("23")))
                .willReturn(okJson("{\"OrderId\":\"S23b\"}")));
        // The close (Market, Amount 23) is rejected.
        wm.stubFor(post(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.OrderType", equalTo("Market")))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody("""
                            {"ErrorInfo":{"ErrorCode":"OrderRejected","Message":"close rejected by broker"}}
                            """)));
        // sl-46's full-size restore (46) succeeds.
        wm.stubFor(post(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.OrderType", equalTo("StopIfTraded")))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("46.0")))
                .willReturn(okJson("{\"OrderId\":\"back-sl\"}")));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/S23a")).willReturn(aResponse().withStatus(200)));
        // S23b's (the take-profit's sized replacement) cancel fails outright — it stays live.
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/S23b")).willReturn(aResponse().withStatus(500)));

        var r = provider.flatten("AAPL", new java.math.BigDecimal("0.5"), null);

        assertThat(r.accepted()).isFalse();
        // NOT overcommitted, despite 46+23=69 > 46 held — see the test comment above.
        assertThat(r.rejectCode()).isEqualTo("LEG_RESTORE_FAILED_UNPROTECTED");
        assertThat(r.protectiveLegs()).hasSize(2);
        assertThat(r.protectiveLegs()).extracting("replaces", "orderId")
                .containsExactlyInAnyOrder(
                        tuple("sl-46", "back-sl"),
                        tuple("tp-46", "S23b"));

        // The take-profit's full-size restore (46) must never have been attempted — the loop
        // stopped at S23b's failed cancel.
        wm.verify(0, postRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.OrderType", equalTo("Limit")))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("46.0"))));
    }

    @Test
    void anIndeterminateCloseLeavesTheReducedLegsInPlace() {
        // The closing Market POST comes back 500 — the broker may have accepted it and only the
        // response was lost. Growing the legs back to full size would risk a naked reverse
        // position if the close actually filled, so this must escalate WITHOUT restoring, and
        // the 12/11 sized legs already placed must be left exactly as they are.
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":46.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":40.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"leg-24","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":24.0,"Price":45.49,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}},
              {"OrderId":"leg-22","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":22.0,"Price":45.49,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}}]}
            """)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-24")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-22")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("indeterminate-500")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("{\"OrderId\":\"R12\"}"))
                .willSetStateTo("sized1"));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("indeterminate-500")
                .whenScenarioStateIs("sized1")
                .willReturn(okJson("{\"OrderId\":\"R11\"}"))
                .willSetStateTo("sized2"));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("indeterminate-500")
                .whenScenarioStateIs("sized2")
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> provider.flatten("AAPL", new java.math.BigDecimal("0.5"), null))
                .isInstanceOfSatisfying(BrokerException.class,
                        e -> assertThat(e.kind()).isEqualTo(BrokerException.Kind.UNAVAILABLE));

        // Exactly 3 POSTs total: the two sized restores and the failed close attempt. NO
        // further POST — the legs stay at 12/11, sized to the remainder, not grown back.
        var posts = requestJournalInOrder().stream()
                .filter(req -> "POST".equals(req.getMethod().value())).toList();
        assertThat(posts).hasSize(3);
        wm.verify(0, postRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("24.0"))));
        wm.verify(0, postRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("22.0"))));
    }

    @Test
    void aTimedOutCloseLeavesTheReducedLegsInPlace() {
        // A transport-level failure (connection reset, timeout, …) on the closing POST is the
        // same "outcome unknown" case as a 500 — the request may already have reached Saxo.
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":46.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":40.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"leg-24","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":24.0,"Price":45.49,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}},
              {"OrderId":"leg-22","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":22.0,"Price":45.49,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}}]}
            """)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-24")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-22")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("timeout-close")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("{\"OrderId\":\"R12\"}"))
                .willSetStateTo("sized1"));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("timeout-close")
                .whenScenarioStateIs("sized1")
                .willReturn(okJson("{\"OrderId\":\"R11\"}"))
                .willSetStateTo("sized2"));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("timeout-close")
                .whenScenarioStateIs("sized2")
                .willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> provider.flatten("AAPL", new java.math.BigDecimal("0.5"), null))
                .isInstanceOfSatisfying(BrokerException.class,
                        e -> assertThat(e.kind()).isEqualTo(BrokerException.Kind.UNAVAILABLE));

        wm.verify(0, postRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("24.0"))));
        wm.verify(0, postRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("22.0"))));
    }

    @Test
    void aFailedRollbackReportsUnprotected() {
        // The closing POST fails determinately (400), and leg-24's full-size restore ALSO
        // fails. Under the interleaved algorithm (fix round 2) the loop stops right there —
        // leg-22's sized replacement (R11) is never even touched and stays live, so the
        // second rollback POST stub below is intentionally never reached. Must still be
        // flagged loudly as unprotected (24 shares of gap for leg-24), not silently reported
        // as a plain restore failure.
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":46.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":40.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"leg-24","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":24.0,"Price":45.49,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}},
              {"OrderId":"leg-22","Uic":211,"OpenOrderType":"StopIfTraded","BuySell":"Sell",
               "Amount":22.0,"Price":45.49,"AssetType":"Stock","Duration":{"DurationType":"GoodTillCancel"}}]}
            """)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-24")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/leg-22")).willReturn(aResponse().withStatus(200)));
        // The sized orphans (R12/R11) cancel cleanly — this test is about the FULL-SIZE restore
        // failing, not the orphan cancel.
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/R12")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/R11")).willReturn(aResponse().withStatus(200)));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("failed-rollback")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("{\"OrderId\":\"R12\"}"))
                .willSetStateTo("sized1"));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("failed-rollback")
                .whenScenarioStateIs("sized1")
                .willReturn(okJson("{\"OrderId\":\"R11\"}"))
                .willSetStateTo("sized2"));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("failed-rollback")
                .whenScenarioStateIs("sized2")
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody("""
                            {"ErrorInfo":{"ErrorCode":"OrderRejected","Message":"close rejected by broker"}}
                            """))
                .willSetStateTo("close-failed"));
        // Both full-size restore attempts fail too — the legs are gone.
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("failed-rollback")
                .whenScenarioStateIs("close-failed")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("rollback1-failed"));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders")).inScenario("failed-rollback")
                .whenScenarioStateIs("rollback1-failed")
                .willReturn(aResponse().withStatus(500)));

        Logger logger = (Logger) LoggerFactory.getLogger(SaxoBrokerProvider.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        OrderResult r;
        try {
            r = provider.flatten("AAPL", new java.math.BigDecimal("0.5"), null);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectCode()).isEqualTo("LEG_RESTORE_FAILED_UNPROTECTED");
        // R11 (leg-22's sized replacement, 11 shares) was never touched and stays live — the
        // loop stopped after leg-24's full-size restore failed, never reaching leg-22's pair.
        assertThat(r.protectiveLegs()).hasSize(1);
        assertThat(r.protectiveLegs().get(0).orderId()).isEqualTo("R11");
        wm.verify(0, deleteRequestedFor(urlPathEqualTo("/trade/v2/orders/R11")));
        wm.verify(0, postRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("22.0"))));

        assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.ERROR
                && e.getFormattedMessage().contains("AAPL")
                && e.getFormattedMessage().contains("could not be"));
    }

    // ---- placeProtectiveStop: additive-only single stop for an existing position ----

    @Test
    void placeProtectiveStopSendsSellStopForLongPosition() {
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":46.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":150.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders"))
                .willReturn(okJson("{\"OrderId\":\"9200\"}")));

        var r = provider.placeProtectiveStop("AAPL", new java.math.BigDecimal("34"), new java.math.BigDecimal("45.49"));

        assertThat(r.accepted()).isTrue();
        assertThat(r.brokerOrderId()).isEqualTo("9200");
        wm.verify(postRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.BuySell", equalTo("Sell")))
                .withRequestBody(matchingJsonPath("$.OrderType", equalTo("StopIfTraded")))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("34")))
                .withRequestBody(matchingJsonPath("$.OrderPrice", equalTo("45.49")))
                .withRequestBody(matchingJsonPath("$.OrderDuration.DurationType", equalTo("GoodTillCancel"))));
        wm.verify(0, deleteRequestedFor(urlPathMatching("/trade/v2/orders/.*")));
    }

    @Test
    void placeProtectiveStopSendsBuyStopForShortPosition() {
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":-46.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":150.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders"))
                .willReturn(okJson("{\"OrderId\":\"9201\"}")));

        var r = provider.placeProtectiveStop("AAPL", new java.math.BigDecimal("10"), new java.math.BigDecimal("160"));

        assertThat(r.accepted()).isTrue();
        wm.verify(postRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.BuySell", equalTo("Buy")))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("10")))
                .withRequestBody(matchingJsonPath("$.OrderPrice", equalTo("160"))));
        wm.verify(0, deleteRequestedFor(urlPathMatching("/trade/v2/orders/.*")));
    }

    @Test
    void placeProtectiveStopWithQtyExceedingPositionIsRejectedWithoutPlacingAnOrder() {
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":46.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":150.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));

        var r = provider.placeProtectiveStop("AAPL", new java.math.BigDecimal("50"), new java.math.BigDecimal("45.49"));

        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectCode()).isEqualTo("QTY_EXCEEDS_POSITION");
        wm.verify(0, postRequestedFor(urlEqualTo("/trade/v2/orders")));
        wm.verify(0, deleteRequestedFor(urlPathMatching("/trade/v2/orders/.*")));
    }

    /**
     * qty == position (46 of 46) is the boundary between correct and over-protective — the
     * ONE comparison that separates "cover exactly what's held" from "place more protective
     * interest than shares exist". Must be accepted, not rejected as exceeding the position.
     */
    @Test
    void placeProtectiveStopWithQtyEqualToPositionIsAccepted() {
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":46.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":150.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders"))
                .willReturn(okJson("{\"OrderId\":\"9203\"}")));

        var r = provider.placeProtectiveStop("AAPL", new java.math.BigDecimal("46"), new java.math.BigDecimal("45.49"));

        assertThat(r.accepted()).isTrue();
        wm.verify(postRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.Amount", equalTo("46"))));
    }

    /**
     * A 409 (duplicate X-Request-ID replay) is INDETERMINATE, not a definite rejection — Saxo
     * may already have placed the stop and only the confirmation was lost (exactly the
     * scenario a rate-limit auto-retry produces). Reporting this as accepted:false would let a
     * caller retry and double the protective interest against the position, so this must throw
     * rather than come back through the plain reject path — mirroring flatten's handling of the
     * same status on its closing POST.
     */
    @Test
    void placeProtectiveStop409IsUnavailableNotARejection() {
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":46.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":150.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders"))
                .willReturn(aResponse().withStatus(409)));

        assertThatThrownBy(() -> provider.placeProtectiveStop("AAPL", new java.math.BigDecimal("34"), new java.math.BigDecimal("45.49")))
                .isInstanceOf(BrokerException.class)
                .extracting(e -> ((BrokerException) e).kind())
                .isEqualTo(BrokerException.Kind.UNAVAILABLE);
    }

    /**
     * A plain 429 (rate-limited, nothing placed) is DETERMINATE and must still come back as a
     * definite rejection, not an outage — this is the "already handled correctly" case that
     * must not regress while fixing the 409/5xx indeterminate handling above.
     */
    @Test
    void placeProtectiveStop429IsADefiniteRejection() {
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":46.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":150.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders"))
                .willReturn(aResponse().withStatus(429)));

        var r = provider.placeProtectiveStop("AAPL", new java.math.BigDecimal("34"), new java.math.BigDecimal("45.49"));

        assertThat(r.accepted()).isFalse();
    }

    @Test
    void placeProtectiveStopWithoutPositionIsNotFound() {
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions"))
                .willReturn(okJson("{\"Data\":[]}")));

        assertThatThrownBy(() -> provider.placeProtectiveStop("AAPL", new java.math.BigDecimal("10"), new java.math.BigDecimal("45.49")))
                .isInstanceOf(BrokerException.class)
                .extracting(e -> ((BrokerException) e).kind())
                .isEqualTo(BrokerException.Kind.NOT_FOUND);
        wm.verify(0, postRequestedFor(urlEqualTo("/trade/v2/orders")));
    }

    @Test
    void placeProtectiveStopWithNonPositiveQtyIsRejectedWithoutAnyCall() {
        var r = provider.placeProtectiveStop("AAPL", new java.math.BigDecimal("0"), new java.math.BigDecimal("45.49"));

        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectCode()).isEqualTo("INVALID_QTY");
        wm.verify(0, getRequestedFor(urlPathEqualTo("/port/v1/netpositions")));
        wm.verify(0, postRequestedFor(urlEqualTo("/trade/v2/orders")));
    }

    @Test
    void placeProtectiveStopSerialisesTheNewOrderId() {
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).willReturn(okJson("""
            {"Data":[{"NetPositionBase":{"Amount":46.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":150.0,"ExposureCurrency":"USD"},
                      "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}]}
            """)));
        wm.stubFor(post(urlEqualTo("/trade/v2/orders"))
                .willReturn(okJson("{\"OrderId\":\"9202\"}")));

        var r = provider.placeProtectiveStop("AAPL", new java.math.BigDecimal("34"), new java.math.BigDecimal("45.49"));

        assertThat(r.accepted()).isTrue();
        assertThat(r.brokerOrderId()).isEqualTo("9202");
        assertThat(r.status()).isEqualTo("accepted");
    }

    // ---- pagination ----

    @Test
    void ordersFollowsNextPaginationLink() {
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).withQueryParam("FieldGroups", equalTo("DisplayAndFormat"))
                .willReturn(okJson("""
                    {"Data":[{"OrderId":"1","OpenOrderType":"Limit","Status":"Working","BuySell":"Buy","Amount":1.0,
                              "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}],
                     "__next":"%s/port/v1/orders/me?$skip=1"}
                    """.formatted(wm.baseUrl()))));
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).withQueryParam("$skip", equalTo("1"))
                .willReturn(okJson("""
                    {"Data":[{"OrderId":"2","OpenOrderType":"Limit","Status":"Working","BuySell":"Buy","Amount":1.0,
                              "DisplayAndFormat":{"Symbol":"MSFT:xnas"}}]}
                    """)));

        var all = provider.orders(null);

        assertThat(all).hasSize(2);
        assertThat(all).extracting("brokerOrderId").containsExactlyInAnyOrder("1", "2");
        wm.verify(1, getRequestedFor(urlPathEqualTo("/port/v1/orders/me")).withQueryParam("FieldGroups", equalTo("DisplayAndFormat")));
        wm.verify(1, getRequestedFor(urlPathEqualTo("/port/v1/orders/me")).withQueryParam("$skip", equalTo("1")));
    }

    @Test
    void positionsFollowsNextPaginationLink() {
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).withQueryParam("ClientKey", equalTo("Cli+Key/1=="))
                .willReturn(okJson("""
                    {"Data":[{"NetPositionBase":{"Amount":10.0,"Uic":211,"AssetType":"Stock"},
                              "NetPositionView":{"AverageOpenPrice":150.0,"ExposureCurrency":"USD"},
                              "DisplayAndFormat":{"Symbol":"AAPL:xnas"}}],
                     "__next":"%s/port/v1/netpositions?$skip=1"}
                    """.formatted(wm.baseUrl()))));
        wm.stubFor(get(urlPathEqualTo("/port/v1/netpositions")).withQueryParam("$skip", equalTo("1"))
                .willReturn(okJson("""
                    {"Data":[{"NetPositionBase":{"Amount":5.0,"Uic":212,"AssetType":"Stock"},
                              "NetPositionView":{"AverageOpenPrice":50.0,"ExposureCurrency":"USD"},
                              "DisplayAndFormat":{"Symbol":"MSFT:xnas"}}]}
                    """)));

        var ps = provider.positions();

        assertThat(ps).hasSize(2);
        assertThat(ps).extracting("symbol").containsExactlyInAnyOrder("AAPL", "MSFT");
    }

    // ---- modifyBracket ----

    private void stubBracketChildren() {
        // SIM-verified shape: pre-fill, only the parent is top-level (IfDoneMaster);
        // children are embedded in RelatedOpenOrders (OrderPrice there, not Price).
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"9001","OpenOrderType":"Limit","Status":"Working","Uic":211,"AssetType":"Stock",
               "BuySell":"Buy","Amount":1.0,"OrderRelation":"IfDoneMaster","Price":100.0,
               "DisplayAndFormat":{"Symbol":"AAPL:xnas"},
               "RelatedOpenOrders":[
                 {"OrderId":"9002","OpenOrderType":"Limit","OrderPrice":110.0,"Status":"NotWorking",
                  "Amount":1.0,"Duration":{"DurationType":"GoodTillCancel"}},
                 {"OrderId":"9003","OpenOrderType":"StopIfTraded","OrderPrice":90.0,"Status":"NotWorking",
                  "Amount":1.0,"Duration":{"DurationType":"GoodTillCancel"}}]}
            ]}
            """)));
    }

    @Test
    void modifyBracketPatchesStopAndTargetLegs() {
        stubBracketChildren();
        wm.stubFor(patch(urlEqualTo("/trade/v2/orders")).willReturn(okJson("{\"OrderId\":\"9002\"}")));

        var r = provider.modifyBracket("9001", "AAPL",
                new java.math.BigDecimal("85"), new java.math.BigDecimal("115"));

        assertThat(r.accepted()).isTrue();
        assertThat(r.status()).isEqualTo("replaced");
        wm.verify(2, patchRequestedFor(urlEqualTo("/trade/v2/orders")));
        wm.verify(patchRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.OrderId", equalTo("9003")))
                .withRequestBody(matchingJsonPath("$.OrderType", equalTo("StopIfTraded")))
                .withRequestBody(matchingJsonPath("$.OrderPrice", equalTo("85")))
                .withRequestBody(matchingJsonPath("$.OrderDuration.DurationType", equalTo("GoodTillCancel"))));
        wm.verify(patchRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.OrderId", equalTo("9002")))
                .withRequestBody(matchingJsonPath("$.OrderType", equalTo("Limit")))
                .withRequestBody(matchingJsonPath("$.OrderPrice", equalTo("115"))));
        // Minimal PATCH body (SIM-verified): no Uic, no Amount, no BuySell
        wm.verify(patchRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(notMatching(".*\"Uic\".*")));
    }

    @Test
    void modifyBracketOnlyStopPatchesOneLeg() {
        stubBracketChildren();
        wm.stubFor(patch(urlEqualTo("/trade/v2/orders")).willReturn(okJson("{\"OrderId\":\"9003\"}")));
        var r = provider.modifyBracket("9001", "AAPL", new java.math.BigDecimal("85"), null);
        assertThat(r.accepted()).isTrue();
        wm.verify(1, patchRequestedFor(urlEqualTo("/trade/v2/orders")));
    }

    @Test
    void modifyBracketWithoutChildrenIsRejectedLegNotFound() {
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me"))
                .willReturn(okJson("{\"Data\":[]}")));
        var r = provider.modifyBracket("9001", "AAPL", new java.math.BigDecimal("85"), null);
        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectCode()).isEqualTo("LEG_NOT_FOUND");
        wm.verify(0, patchRequestedFor(urlEqualTo("/trade/v2/orders")));
    }

    @Test
    void modifyBracket_postFill_fallsBackToSymbolLegs() {
        // stubInstrument() maps AAPL -> Uic 211 (see helper above)
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"sl-77","Uic":211,"OpenOrderType":"Stop","Duration":{"DurationType":"GoodTillCancel"}},
              {"OrderId":"tp-77","Uic":211,"OpenOrderType":"Limit","Duration":{"DurationType":"GoodTillCancel"}}]}""")));
        wm.stubFor(patch(urlEqualTo("/trade/v2/orders")).willReturn(okJson("{\"OrderId\":\"sl-77\"}")));

        var r = provider.modifyBracket("gone-parent", "AAPL", new java.math.BigDecimal("95"), null);
        assertThat(r.accepted()).isTrue();
        wm.verify(patchRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.OrderId", equalTo("sl-77"))));
    }

    @Test
    void modifyBracket_postFill_childlessParentIsExcludedFromSelfMatch() {
        // Parent present in Data[] (same Uic as the resolved symbol) but with an EMPTY
        // RelatedOpenOrders — this triggers the symbol fallback. No other working orders
        // share the Uic. The requested modification is a TARGET (not a stop) because the
        // parent's own OpenOrderType is "Limit" — the same type the fallback uses to
        // classify take-profit legs. Without excluding the caller's own parent id, the
        // fallback would misclassify the parent itself as the take-profit leg and PATCH it,
        // corrupting the entry price. It must instead find nothing and return a typed
        // LEG_NOT_FOUND rejection (uniform with Alpaca — not a thrown exception).
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"9001","OpenOrderType":"Limit","Status":"Working","Uic":211,"AssetType":"Stock",
               "BuySell":"Buy","Amount":1.0,"OrderRelation":"IfDoneMaster","Price":100.0,
               "DisplayAndFormat":{"Symbol":"AAPL:xnas"},
               "RelatedOpenOrders":[]}
            ]}
            """)));

        var r = provider.modifyBracket("9001", "AAPL", null, new java.math.BigDecimal("115"));
        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectCode()).isEqualTo("LEG_NOT_FOUND");
        wm.verify(0, patchRequestedFor(urlEqualTo("/trade/v2/orders")));
    }

    @Test
    void modifyBracketPatch400IsRejected() {
        stubBracketChildren();
        wm.stubFor(patch(urlEqualTo("/trade/v2/orders")).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"ErrorInfo\":{\"ErrorCode\":\"TooLateToChange\",\"Message\":\"too late\"}}")));
        var r = provider.modifyBracket("9001", "AAPL", new java.math.BigDecimal("85"), null);
        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectCode()).isEqualTo("TooLateToChange");
    }

    @Test
    void modifyBracketBothNullIsRejectedWithoutAnyCall() {
        // Guard: both stop and target null → rejected without hitting /port/v1/orders/me
        var r = provider.modifyBracket("9001", "AAPL", null, null);
        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectCode()).isEqualTo("NO_CHANGES");
        assertThat(r.rejectReason()).containsIgnoringCase("nothing to modify");
        wm.verify(0, getRequestedFor(urlPathEqualTo("/port/v1/orders/me")));
        wm.verify(0, patchRequestedFor(urlEqualTo("/trade/v2/orders")));
    }

    @Test
    void modifyBracketMissingStopLegIsRejected() {
        // Stub: parent with only TP (Limit) child, no SL child
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"9001","OpenOrderType":"Limit","Status":"Working","Uic":211,"AssetType":"Stock",
               "BuySell":"Buy","Amount":1.0,"OrderRelation":"IfDoneMaster","Price":100.0,
               "DisplayAndFormat":{"Symbol":"AAPL:xnas"},
               "RelatedOpenOrders":[
                 {"OrderId":"9002","OpenOrderType":"Limit","OrderPrice":110.0,"Status":"NotWorking",
                  "Amount":1.0,"Duration":{"DurationType":"GoodTillCancel"}}]}
            ]}
            """)));
        // Request stop modification when no SL leg exists
        var r = provider.modifyBracket("9001", "AAPL", new java.math.BigDecimal("85"), null);
        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectCode()).isEqualTo("LEG_NOT_FOUND");
        assertThat(r.rejectReason()).containsIgnoringCase("no stop-loss leg");
        wm.verify(0, patchRequestedFor(urlEqualTo("/trade/v2/orders")));
    }

    @Test
    void modifyBracketPatchPreservesLegAssetTypeFromLookup() {
        // Leg carries its own AssetType (e.g. an options/futures bracket, not a plain
        // Stock) — patchLeg must send that, not a hardcoded "Stock".
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"9001","OpenOrderType":"Limit","Status":"Working","Uic":211,"AssetType":"Stock",
               "BuySell":"Buy","Amount":1.0,"OrderRelation":"IfDoneMaster","Price":100.0,
               "DisplayAndFormat":{"Symbol":"AAPL:xnas"},
               "RelatedOpenOrders":[
                 {"OrderId":"9003","OpenOrderType":"StopIfTraded","OrderPrice":90.0,"Status":"NotWorking",
                  "Amount":1.0,"AssetType":"StockIndexOption","Duration":{"DurationType":"GoodTillCancel"}}]}
            ]}
            """)));
        wm.stubFor(patch(urlEqualTo("/trade/v2/orders")).willReturn(okJson("{\"OrderId\":\"9003\"}")));

        var r = provider.modifyBracket("9001", "AAPL", new java.math.BigDecimal("85"), null);

        assertThat(r.accepted()).isTrue();
        wm.verify(patchRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.AssetType", equalTo("StockIndexOption"))));
    }

    @Test
    void modifyBracketPatchPreservesGtdExpiry() {
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"9001","OpenOrderType":"Limit","Status":"Working","Uic":211,"AssetType":"Stock",
               "BuySell":"Buy","Amount":1.0,"OrderRelation":"IfDoneMaster","Price":100.0,
               "DisplayAndFormat":{"Symbol":"AAPL:xnas"},
               "RelatedOpenOrders":[
                 {"OrderId":"9003","OpenOrderType":"StopIfTraded","OrderPrice":90.0,"Status":"NotWorking",
                  "Amount":1.0,"Duration":{"DurationType":"GoodTillDate","ExpirationDateTime":"2026-08-01T00:00:00Z"}}]}
            ]}
            """)));
        wm.stubFor(patch(urlEqualTo("/trade/v2/orders")).willReturn(okJson("{\"OrderId\":\"9003\"}")));

        var r = provider.modifyBracket("9001", "AAPL", new java.math.BigDecimal("85"), null);

        assertThat(r.accepted()).isTrue();
        wm.verify(patchRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.OrderDuration.DurationType", equalTo("GoodTillDate")))
                .withRequestBody(matchingJsonPath("$.OrderDuration.ExpirationDateTime", equalTo("2026-08-01T00:00:00Z"))));
    }

    @Test
    void modifyBracketMissingTargetLegIsRejected() {
        // Stub: parent with only SL (StopIfTraded) child, no TP child
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"9001","OpenOrderType":"Limit","Status":"Working","Uic":211,"AssetType":"Stock",
               "BuySell":"Buy","Amount":1.0,"OrderRelation":"IfDoneMaster","Price":100.0,
               "DisplayAndFormat":{"Symbol":"AAPL:xnas"},
               "RelatedOpenOrders":[
                 {"OrderId":"9003","OpenOrderType":"StopIfTraded","OrderPrice":90.0,"Status":"NotWorking",
                  "Amount":1.0,"Duration":{"DurationType":"GoodTillCancel"}}]}
            ]}
            """)));
        // Request target modification when no TP leg exists
        var r = provider.modifyBracket("9001", "AAPL", null, new java.math.BigDecimal("115"));
        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectCode()).isEqualTo("LEG_NOT_FOUND");
        assertThat(r.rejectReason()).containsIgnoringCase("no take-profit leg");
        wm.verify(0, patchRequestedFor(urlEqualTo("/trade/v2/orders")));
    }

    // ---- modifyBracket: explicit leg addressing (two-tranche positions) ----

    /**
     * Prod shape, verified on the paper book 2026-08-04: a two-tranche position leaves TWO
     * working detached StopIfTraded orders on the same Uic. The by-symbol fallback keeps the
     * last one it scans, so only an explicit leg id can say which stop is being moved.
     */
    private void stubTwoDetachedStopsOnOneUic() {
        stubInstrument();
        wm.stubFor(get(urlPathEqualTo("/port/v1/orders/me")).willReturn(okJson("""
            {"Data":[
              {"OrderId":"stop-t1","Uic":211,"OpenOrderType":"StopIfTraded","Status":"Working",
               "AssetType":"Stock","Amount":24.0,"Price":45.34,
               "Duration":{"DurationType":"GoodTillCancel"}},
              {"OrderId":"stop-t2","Uic":211,"OpenOrderType":"StopIfTraded","Status":"Working",
               "AssetType":"Stock","Amount":22.0,"Price":45.34,
               "Duration":{"DurationType":"GoodTillCancel"}}]}
            """)));
    }

    @Test
    void modifyBracket_explicitStopOrderId_patchesThatLegAmongTwoOnTheSameUic() {
        stubTwoDetachedStopsOnOneUic();
        wm.stubFor(patch(urlEqualTo("/trade/v2/orders")).willReturn(okJson("{\"OrderId\":\"stop-t2\"}")));

        var r = provider.modifyBracket("gone-parent", "AAPL", new java.math.BigDecimal("46.10"), null,
                "stop-t2", null);

        assertThat(r.accepted()).isTrue();
        wm.verify(1, patchRequestedFor(urlEqualTo("/trade/v2/orders")));
        wm.verify(patchRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.OrderId", equalTo("stop-t2")))
                .withRequestBody(matchingJsonPath("$.OrderType", equalTo("StopIfTraded")))
                .withRequestBody(matchingJsonPath("$.OrderPrice", equalTo("46.1"))));
    }

    @Test
    void modifyBracket_explicitStopOrderId_findsLegEmbeddedInRelatedOpenOrders() {
        // The other prod shape: the second tranche's entry is still unfilled, so its stop leg
        // is not a top-level order at all — it lives in the parent's RelatedOpenOrders.
        stubBracketChildren();
        wm.stubFor(patch(urlEqualTo("/trade/v2/orders")).willReturn(okJson("{\"OrderId\":\"9003\"}")));

        var r = provider.modifyBracket("9001", "AAPL", new java.math.BigDecimal("85"), null, "9003", null);

        assertThat(r.accepted()).isTrue();
        wm.verify(patchRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.OrderId", equalTo("9003")))
                .withRequestBody(matchingJsonPath("$.OrderPrice", equalTo("85"))));
    }

    @Test
    void modifyBracket_explicitStopOrderId_unknownIdIsRejectedWithoutPatching() {
        stubTwoDetachedStopsOnOneUic();
        var r = provider.modifyBracket("gone-parent", "AAPL", new java.math.BigDecimal("46.10"), null,
                "stop-gone", null);
        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectCode()).isEqualTo("LEG_NOT_FOUND");
        assertThat(r.rejectReason()).contains("stop-gone");
        wm.verify(0, patchRequestedFor(urlEqualTo("/trade/v2/orders")));
    }

    @Test
    void modifyBracket_explicitStopOrderId_pointingAtANonStopOrderIsRejected() {
        // Naming the ENTRY order must never re-price the entry.
        stubBracketChildren();
        var r = provider.modifyBracket("9001", "AAPL", new java.math.BigDecimal("85"), null, "9001", null);
        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectCode()).isEqualTo("LEG_TYPE_MISMATCH");
        wm.verify(0, patchRequestedFor(urlEqualTo("/trade/v2/orders")));
    }

    @Test
    void modifyBracket_explicitTargetOrderId_patchesThatLeg() {
        stubBracketChildren();
        wm.stubFor(patch(urlEqualTo("/trade/v2/orders")).willReturn(okJson("{\"OrderId\":\"9002\"}")));
        var r = provider.modifyBracket("9001", "AAPL", null, new java.math.BigDecimal("115"), null, "9002");
        assertThat(r.accepted()).isTrue();
        wm.verify(patchRequestedFor(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.OrderId", equalTo("9002")))
                .withRequestBody(matchingJsonPath("$.OrderPrice", equalTo("115"))));
    }

    @Test
    void modifyBracket_namingOneLegButNotTheOtherPricedLegIsRejected() {
        // Half-explicit is the worst of both: the named leg is safe, the unnamed one would fall
        // back to exactly the guess the caller is paying to avoid. Reject instead.
        stubBracketChildren();
        var r = provider.modifyBracket("9001", "AAPL", new java.math.BigDecimal("85"),
                new java.math.BigDecimal("115"), "9003", null);
        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectCode()).isEqualTo("LEG_ID_REQUIRED");
        wm.verify(0, patchRequestedFor(urlEqualTo("/trade/v2/orders")));
    }

    @Test
    void modifyBracket_explicitLegs_targetFailureAfterStopMovedIsReportedAsPartial() {
        stubBracketChildren();
        wm.stubFor(patch(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.OrderId", equalTo("9003")))
                .willReturn(okJson("{\"OrderId\":\"9003\"}")));
        wm.stubFor(patch(urlEqualTo("/trade/v2/orders"))
                .withRequestBody(matchingJsonPath("$.OrderId", equalTo("9002")))
                .willReturn(aResponse().withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"ErrorInfo\":{\"ErrorCode\":\"TooLateToChange\",\"Message\":\"too late\"}}")));

        var r = provider.modifyBracket("9001", "AAPL", new java.math.BigDecimal("85"),
                new java.math.BigDecimal("115"), "9003", "9002");

        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectReason()).contains("stop-loss was already moved");
        assertThat(r.rejectCode()).isEqualTo("TooLateToChange");
    }
}
