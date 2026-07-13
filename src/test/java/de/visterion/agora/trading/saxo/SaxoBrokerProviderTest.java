package de.visterion.agora.trading.saxo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
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
        provider = new SaxoBrokerProvider(cfg, store, RestClient.builder().baseUrl(wm.baseUrl()).build(),
                resolver());
        provider.legLookupDelayMillis = 0;   // don't actually sleep in tests
        stubAccounts();
    }

    private SaxoInstrumentResolver resolver() {
        return new SaxoInstrumentResolver(RestClient.builder().baseUrl(wm.baseUrl()).build(),
                () -> "Bearer acc-token", null, 86_400_000L, now::get);
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
                      "NetPositionBase":{"Amount":10.0,"Uic":211,"AssetType":"Stock"},
                      "NetPositionView":{"AverageOpenPrice":150.0,"Exposure":1510.0,
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
                .withQueryParam("ClientKey", equalTo("Cli+Key/1=="))
                .withQueryParam("AccountKey", equalTo("Acc+Key/1=="))
                .withQueryParam("FieldGroups", equalTo("NetPositionBase,NetPositionView,DisplayAndFormat")));
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
                .withHeader("X-Request-ID", equalTo("ref-1"))
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

    // ---- precheckBracket ----

    private tools.jackson.databind.node.ObjectNode precheckBody() {
        var body = SaxoBrokerProvider.MAPPER.createObjectNode();
        body.put("Uic", 211);
        body.put("AssetType", "Stock");
        body.put("BuySell", "Buy");
        body.put("Amount", 1);
        body.put("OrderType", "Limit");
        body.put("OrderPrice", 100);
        var takeProfit = SaxoBrokerProvider.MAPPER.createObjectNode();
        takeProfit.put("OrderType", "Limit");
        takeProfit.put("OrderPrice", 110);
        takeProfit.put("BuySell", "Sell");
        var stopLoss = SaxoBrokerProvider.MAPPER.createObjectNode();
        stopLoss.put("OrderType", "StopIfTraded");
        stopLoss.put("OrderPrice", 90);
        stopLoss.put("BuySell", "Sell");
        var children = SaxoBrokerProvider.MAPPER.createArrayNode();
        children.add(takeProfit);
        children.add(stopLoss);
        body.set("Orders", children);
        return body;
    }

    @Test
    void precheckBracketCleanBodyIsClean() {
        wm.stubFor(post(urlEqualTo("/trade/v2/orders/precheck")).willReturn(okJson("""
            {"PreCheckResult":"Ok"}
            """)));

        var result = provider.precheckBracket(precheckBody(), "req-1");

        assertThat(result.kind()).isEqualTo(SaxoBrokerProvider.PrecheckOutcome.CLEAN);
        wm.verify(postRequestedFor(urlEqualTo("/trade/v2/orders/precheck"))
                .withHeader("X-Request-ID", equalTo("req-1"))
                .withRequestBody(matchingJsonPath("$.Uic", equalTo("211"))));
    }

    @Test
    void precheckBracketSlLegTooFarIsSlTooFar() {
        wm.stubFor(post(urlEqualTo("/trade/v2/orders/precheck")).willReturn(okJson("""
            {"PreCheckResult":"Error","Orders":[
              {},
              {"ErrorInfo":{"ErrorCode":"TooFarFromEntryOrder","Message":"Order price is too far from the entry order"}}]}
            """)));

        var result = provider.precheckBracket(precheckBody(), "req-2");

        assertThat(result.kind()).isEqualTo(SaxoBrokerProvider.PrecheckOutcome.SL_TOO_FAR);
        assertThat(result.rejectCode()).isEqualTo("TooFarFromEntryOrder");
        wm.verify(postRequestedFor(urlEqualTo("/trade/v2/orders/precheck"))
                .withHeader("X-Request-ID", equalTo("req-2")));
    }

    @Test
    void precheckBracketOtherErrorInfoIsRejected() {
        wm.stubFor(post(urlEqualTo("/trade/v2/orders/precheck")).willReturn(okJson("""
            {"PreCheckResult":"Error","ErrorInfo":{"ErrorCode":"IllegalInstrumentId","Message":"Instrument not tradable"}}
            """)));

        var result = provider.precheckBracket(precheckBody(), "req-3");

        assertThat(result.kind()).isEqualTo(SaxoBrokerProvider.PrecheckOutcome.REJECTED);
        assertThat(result.rejectMessage()).isEqualTo("Instrument not tradable");
        assertThat(result.rejectCode()).isEqualTo("IllegalInstrumentId");
    }

    @Test
    void precheckBracket400WithTooFarErrorInfoIsSlTooFar() {
        wm.stubFor(post(urlEqualTo("/trade/v2/orders/precheck")).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"ErrorInfo":{"ErrorCode":"TooFarFromEntryOrder","Message":"Order price is too far from the entry order"}}
                    """)));

        var result = provider.precheckBracket(precheckBody(), "req-4");

        assertThat(result.kind()).isEqualTo(SaxoBrokerProvider.PrecheckOutcome.SL_TOO_FAR);
    }

    @Test
    void precheckBracket400WithOtherErrorInfoIsRejected() {
        wm.stubFor(post(urlEqualTo("/trade/v2/orders/precheck")).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"ErrorInfo":{"ErrorCode":"IllegalInstrumentId","Message":"Instrument not tradable"}}
                    """)));

        var result = provider.precheckBracket(precheckBody(), "req-5");

        assertThat(result.kind()).isEqualTo(SaxoBrokerProvider.PrecheckOutcome.REJECTED);
        assertThat(result.rejectMessage()).isEqualTo("Instrument not tradable");
        assertThat(result.rejectCode()).isEqualTo("IllegalInstrumentId");
    }

    @Test
    void precheckBracketServerErrorThrowsUnavailable() {
        wm.stubFor(post(urlEqualTo("/trade/v2/orders/precheck")).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> provider.precheckBracket(precheckBody(), "req-6"))
                .isInstanceOfSatisfying(BrokerException.class,
                        e -> assertThat(e.kind()).isEqualTo(BrokerException.Kind.UNAVAILABLE));
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
}
