package de.visterion.agora.trading;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class AlpacaBrokerProviderTest {

    static WireMockServer wm;
    AlpacaBrokerProvider provider;

    @BeforeAll
    static void start() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
    }

    @AfterAll
    static void stop() { wm.stop(); }

    @BeforeEach
    void setUp() {
        wm.resetAll();
        provider = new AlpacaBrokerProvider(wm.baseUrl(), "test-key-id", "test-secret");
    }

    // ---- submitBracket ----

    @Test
    void submitBracket_200_returnsAccepted() {
        wm.stubFor(post(urlEqualTo("/orders"))
                .willReturn(okJson("""
                    {"id":"oid-1","client_order_id":"ref-1","status":"accepted"}
                    """)));

        var req = new BracketOrderRequest(
                "AAPL", "buy", new BigDecimal("10"), "limit", "gtc",
                new BigDecimal("190"), new BigDecimal("185"), null,
                new BigDecimal("200"), "ref-1");

        var result = provider.submitBracket(req);

        assertThat(result.accepted()).isTrue();
        assertThat(result.brokerOrderId()).isEqualTo("oid-1");
        assertThat(result.clientRef()).isEqualTo("ref-1");
        assertThat(result.status()).isEqualTo("accepted");
    }

    @Test
    void submitBracket_200_requestBodyHasRequiredFields() {
        wm.stubFor(post(urlEqualTo("/orders"))
                .willReturn(okJson("""
                    {"id":"oid-2","client_order_id":"ref-2","status":"accepted"}
                    """)));

        var req = new BracketOrderRequest(
                "TSLA", "buy", new BigDecimal("5"), "limit", "day",
                new BigDecimal("250"), new BigDecimal("240"), null,
                new BigDecimal("270"), "ref-2");

        provider.submitBracket(req);

        wm.verify(postRequestedFor(urlEqualTo("/orders"))
                .withHeader("APCA-API-KEY-ID", equalTo("test-key-id"))
                .withHeader("APCA-API-SECRET-KEY", equalTo("test-secret"))
                .withRequestBody(matchingJsonPath("$.order_class", equalTo("bracket")))
                .withRequestBody(matchingJsonPath("$.stop_loss.stop_price"))
                .withRequestBody(matchingJsonPath("$.take_profit.limit_price"))
                .withRequestBody(matchingJsonPath("$.client_order_id", equalTo("ref-2"))));
    }

    @Test
    void submitBracket_403_returnsRejected() {
        wm.stubFor(post(urlEqualTo("/orders"))
                .willReturn(aResponse().withStatus(403)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {"message":"insufficient buying power"}
                            """)));

        var req = new BracketOrderRequest(
                "AAPL", "buy", new BigDecimal("1000"), "limit", "gtc",
                null, new BigDecimal("180"), null,
                new BigDecimal("210"), "ref-3");

        var result = provider.submitBracket(req);

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectReason()).isEqualTo("insufficient buying power");
        assertThat(result.rejectCode()).isEqualTo("403");
    }

    @Test
    void submitBracket_422_returnsRejected() {
        wm.stubFor(post(urlEqualTo("/orders"))
                .willReturn(aResponse().withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {"message":"invalid parameters"}
                            """)));

        var req = new BracketOrderRequest(
                "AAPL", "buy", new BigDecimal("1"), "limit", "gtc",
                null, new BigDecimal("180"), null,
                new BigDecimal("210"), "ref-4");

        var result = provider.submitBracket(req);

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectCode()).isEqualTo("422");
    }

    @Test
    void submitBracket_500_throwsUnavailable() {
        wm.stubFor(post(urlEqualTo("/orders"))
                .willReturn(aResponse().withStatus(500)));

        var req = new BracketOrderRequest(
                "AAPL", "buy", new BigDecimal("1"), "limit", "gtc",
                null, new BigDecimal("180"), null,
                new BigDecimal("210"), "ref-5");

        assertThatThrownBy(() -> provider.submitBracket(req))
                .isInstanceOfSatisfying(BrokerException.class, ex ->
                        assertThat(ex.kind()).isEqualTo(BrokerException.Kind.UNAVAILABLE));
    }

    @Test
    void submitBracket_200_parsesLegIdsFromLegsArray() {
        wm.stubFor(post(urlEqualTo("/orders"))
                .willReturn(okJson("""
                    {"id":"oid-1","client_order_id":"ref-1","status":"accepted",
                     "legs":[{"id":"leg-stop","type":"stop"},{"id":"leg-limit","type":"limit"}]}
                    """)));

        var req = new BracketOrderRequest(
                "AAPL", "buy", new BigDecimal("10"), "limit", "gtc",
                new BigDecimal("190"), new BigDecimal("185"), null,
                new BigDecimal("200"), "ref-1");

        var result = provider.submitBracket(req);

        assertThat(result.accepted()).isTrue();
        assertThat(result.stopLegId()).isEqualTo("leg-stop");
        assertThat(result.takeProfitLegId()).isEqualTo("leg-limit");
    }

    @Test
    void submitBracket_200_noLegsArray_legIdsNull() {
        wm.stubFor(post(urlEqualTo("/orders"))
                .willReturn(okJson("""
                    {"id":"oid-1","client_order_id":"ref-1","status":"accepted"}
                    """)));

        var req = new BracketOrderRequest(
                "AAPL", "buy", new BigDecimal("10"), "limit", "gtc",
                new BigDecimal("190"), new BigDecimal("185"), null,
                new BigDecimal("200"), "ref-1");

        var result = provider.submitBracket(req);

        assertThat(result.accepted()).isTrue();
        assertThat(result.stopLegId()).isNull();
        assertThat(result.takeProfitLegId()).isNull();
    }

    // ---- modifyBracket ----

    @Test
    void modifyBracket_200_returnsAccepted() {
        wm.stubFor(patch(urlEqualTo("/orders/oid-1"))
                .willReturn(okJson("""
                    {"id":"oid-1","client_order_id":"ref-1","status":"replaced"}
                    """)));

        var result = provider.modifyBracket("oid-1", new BigDecimal("183"), new BigDecimal("202"));

        assertThat(result.accepted()).isTrue();
        assertThat(result.brokerOrderId()).isEqualTo("oid-1");
        assertThat(result.status()).isEqualTo("replaced");
    }

    // ---- flatten ----

    @Test
    void flatten_200_returnsAccepted() {
        wm.stubFor(delete(urlEqualTo("/positions/AAPL"))
                .willReturn(okJson("""
                    {"id":"oid-9","client_order_id":null,"status":"accepted"}
                    """)));

        var result = provider.flatten("AAPL", null, null);

        assertThat(result.accepted()).isTrue();
    }

    @Test
    void flatten_422_returnsRejected_notThrows() {
        wm.stubFor(delete(urlEqualTo("/positions/AAPL"))
                .willReturn(aResponse().withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {"message":"position has pending orders"}
                            """)));

        var result = provider.flatten("AAPL", null, null);

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectCode()).isEqualTo("422");
        assertThat(result.rejectReason()).contains("pending orders");
    }

    @Test
    void flatten_403_returnsRejected_notThrows() {
        wm.stubFor(delete(urlEqualTo("/positions/GOOG"))
                .willReturn(aResponse().withStatus(403)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {"message":"not permitted"}
                            """)));

        var result = provider.flatten("GOOG", null, null);

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectCode()).isEqualTo("403");
    }

    @Test
    void flatten_404_throwsNotFound() {
        wm.stubFor(delete(urlEqualTo("/positions/ZZZZ"))
                .willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> provider.flatten("ZZZZ", null, null))
                .isInstanceOfSatisfying(BrokerException.class, ex ->
                        assertThat(ex.kind()).isEqualTo(BrokerException.Kind.NOT_FOUND));
    }

    @Test
    void flatten_withQty_sendsQtyQueryParam() {
        wm.stubFor(delete(urlPathEqualTo("/positions/AAPL"))
                .withQueryParam("qty", equalTo("3"))
                .willReturn(okJson("""
                    {"id":"oid-9","qty":"3","status":"accepted"}
                    """)));

        var result = provider.flatten("AAPL", null, new BigDecimal("3"));

        assertThat(result.accepted()).isTrue();
        assertThat(result.closedQty()).isEqualByComparingTo("3");
        wm.verify(deleteRequestedFor(urlPathEqualTo("/positions/AAPL")).withQueryParam("qty", equalTo("3")));
    }

    @Test
    void flatten_withFraction_sendsPercentageQueryParam() {
        wm.stubFor(delete(urlPathEqualTo("/positions/AAPL"))
                .withQueryParam("percentage", equalTo("50"))
                .willReturn(okJson("""
                    {"id":"oid-9","qty":"5","status":"accepted"}
                    """)));

        var result = provider.flatten("AAPL", new BigDecimal("0.5"), null);

        assertThat(result.accepted()).isTrue();
        assertThat(result.closedQty()).isEqualByComparingTo("5");
        wm.verify(deleteRequestedFor(urlPathEqualTo("/positions/AAPL")).withQueryParam("percentage", equalTo("50")));
    }

    @Test
    void flatten_parsesFilledAvgPriceWhenPresent() {
        wm.stubFor(delete(urlPathEqualTo("/positions/AAPL"))
                .willReturn(okJson("""
                    {"id":"oid-9","qty":"3","filled_avg_price":"101.50","status":"filled"}
                    """)));

        var result = provider.flatten("AAPL", null, new BigDecimal("3"));

        assertThat(result.avgFillPrice()).isEqualByComparingTo("101.50");
        assertThat(result.remainingQty()).isNull();
    }

    // ---- positions() ----

    @Test
    void positions_parsesListCorrectly() {
        wm.stubFor(get(urlEqualTo("/positions"))
                .willReturn(okJson("""
                    [
                      {"symbol":"AAPL","qty":"10","avg_entry_price":"185.50",
                       "market_value":"1900.00","unrealized_pl":"145.00"}
                    ]
                    """)));

        var positions = provider.positions();

        assertThat(positions).hasSize(1);
        var p = positions.get(0);
        assertThat(p.symbol()).isEqualTo("AAPL");
        assertThat(p.qty()).isEqualByComparingTo("10");
        assertThat(p.avgEntryPrice()).isEqualByComparingTo("185.50");
        assertThat(p.marketValue()).isEqualByComparingTo("1900.00");
        assertThat(p.unrealizedPl()).isEqualByComparingTo("145.00");
        assertThat(p.currency()).isEqualTo("USD");
    }

    // ---- orders() ----

    @Test
    void orders_parsesListCorrectly() {
        wm.stubFor(get(urlPathEqualTo("/orders"))
                .willReturn(okJson("""
                    [
                      {"id":"oid-10","client_order_id":"ref-10","symbol":"MSFT",
                       "side":"buy","qty":"3","order_type":"limit","status":"new"}
                    ]
                    """)));

        var orders = provider.orders(null);

        assertThat(orders).hasSize(1);
        var o = orders.get(0);
        assertThat(o.brokerOrderId()).isEqualTo("oid-10");
        assertThat(o.clientRef()).isEqualTo("ref-10");
        assertThat(o.symbol()).isEqualTo("MSFT");
        assertThat(o.side()).isEqualTo("buy");
        assertThat(o.qty()).isEqualByComparingTo("3");
        assertThat(o.status()).isEqualTo("new");
        assertThat(o.role()).isEqualTo("other");
        assertThat(o.parentId()).isNull();
    }

    @Test
    void orders_bracketParentRoleIsEntry() {
        wm.stubFor(get(urlPathEqualTo("/orders"))
                .willReturn(okJson("""
                    [
                      {"id":"oid-10","client_order_id":"ref-10","symbol":"MSFT",
                       "side":"buy","qty":"3","order_type":"limit","status":"new",
                       "order_class":"bracket"}
                    ]
                    """)));

        var orders = provider.orders(null);

        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).role()).isEqualTo("entry");
    }

    @Test
    void orders_flattensLegsWithParentIdAndRole() {
        wm.stubFor(get(urlPathEqualTo("/orders"))
                .willReturn(okJson("""
                    [
                      {"id":"oid-parent","client_order_id":"ref-p","symbol":"MSFT",
                       "side":"buy","qty":"3","order_type":"limit","status":"filled",
                       "order_class":"bracket",
                       "legs":[
                         {"id":"leg-stop","type":"stop","symbol":"MSFT","side":"sell",
                          "qty":"3","status":"new","filled_qty":"0"},
                         {"id":"leg-limit","type":"limit","symbol":"MSFT","side":"sell",
                          "qty":"3","status":"filled","filled_qty":"3","filled_avg_price":"210.00"}
                       ]}
                    ]
                    """)));

        var orders = provider.orders(null);

        assertThat(orders).hasSize(3);
        assertThat(orders.get(0).role()).isEqualTo("entry");
        assertThat(orders.get(0).parentId()).isNull();

        var stopLeg = orders.get(1);
        assertThat(stopLeg.brokerOrderId()).isEqualTo("leg-stop");
        assertThat(stopLeg.role()).isEqualTo("stop_loss");
        assertThat(stopLeg.parentId()).isEqualTo("oid-parent");
        assertThat(stopLeg.filledQty()).isEqualByComparingTo("0");

        var tpLeg = orders.get(2);
        assertThat(tpLeg.brokerOrderId()).isEqualTo("leg-limit");
        assertThat(tpLeg.role()).isEqualTo("take_profit");
        assertThat(tpLeg.parentId()).isEqualTo("oid-parent");
        assertThat(tpLeg.filledQty()).isEqualByComparingTo("3");
        assertThat(tpLeg.avgFillPrice()).isEqualByComparingTo("210.00");
    }

    @Test
    void orders_withStatus_sendsQueryParam() {
        wm.stubFor(get(urlPathEqualTo("/orders"))
                .withQueryParam("status", equalTo("all"))
                .willReturn(okJson("[]")));

        provider.orders("all");

        wm.verify(getRequestedFor(urlPathEqualTo("/orders"))
                .withQueryParam("status", equalTo("all")));
    }

    @Test
    void orders_alwaysRequestsNestedLegs() {
        wm.stubFor(get(urlPathEqualTo("/orders"))
                .willReturn(okJson("[]")));

        provider.orders(null);

        wm.verify(getRequestedFor(urlPathEqualTo("/orders"))
                .withQueryParam("nested", equalTo("true")));
    }

    // ---- account() ----

    @Test
    void account_parsesCorrectly() {
        wm.stubFor(get(urlEqualTo("/account"))
                .willReturn(okJson("""
                    {"id":"acc-123","equity":"50000.00","buying_power":"25000.00",
                     "cash":"10000.00","currency":"USD","status":"ACTIVE"}
                    """)));

        var acc = provider.account();

        assertThat(acc.accountId()).isEqualTo("acc-123");
        assertThat(acc.equity()).isEqualByComparingTo("50000.00");
        assertThat(acc.buyingPower()).isEqualByComparingTo("25000.00");
        assertThat(acc.cash()).isEqualByComparingTo("10000.00");
        assertThat(acc.currency()).isEqualTo("USD");
        assertThat(acc.status()).isEqualTo("ACTIVE");
    }

    // ---- orderByClientRef ----

    @Test
    void orderByClientRef_200_returnsOrder() {
        wm.stubFor(get(urlPathEqualTo("/orders:by_client_order_id"))
                .withQueryParam("client_order_id", equalTo("ref-1"))
                .willReturn(okJson("""
                    {"id":"oid-1","client_order_id":"ref-1","symbol":"AAPL",
                     "side":"buy","qty":"10","order_type":"limit","status":"filled"}
                    """)));

        var order = provider.orderByClientRef("ref-1");

        assertThat(order.brokerOrderId()).isEqualTo("oid-1");
        assertThat(order.clientRef()).isEqualTo("ref-1");
        assertThat(order.symbol()).isEqualTo("AAPL");
        assertThat(order.status()).isEqualTo("filled");
    }

    @Test
    void orderByClientRef_404_throwsNotFound() {
        wm.stubFor(get(urlPathEqualTo("/orders:by_client_order_id"))
                .withQueryParam("client_order_id", equalTo("missing-ref"))
                .willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> provider.orderByClientRef("missing-ref"))
                .isInstanceOfSatisfying(BrokerException.class, ex ->
                        assertThat(ex.kind()).isEqualTo(BrokerException.Kind.NOT_FOUND));
    }

    // ---- cancel ----

    @Test
    void cancel_204_returnsAccepted() {
        wm.stubFor(delete(urlEqualTo("/orders/oid-1")).willReturn(aResponse().withStatus(204)));
        var r = provider.cancel("oid-1");
        assertThat(r.accepted()).isTrue();
        assertThat(r.brokerOrderId()).isEqualTo("oid-1");
        assertThat(r.status()).isEqualTo("canceled");
    }

    @Test
    void cancel_404_throwsNotFound() {
        wm.stubFor(delete(urlEqualTo("/orders/missing")).willReturn(aResponse().withStatus(404)));
        assertThatThrownBy(() -> provider.cancel("missing"))
            .isInstanceOf(BrokerException.class);
    }

    @Test
    void cancel_422_returnsRejected() {
        wm.stubFor(delete(urlEqualTo("/orders/filled"))
            .willReturn(aResponse().withStatus(422).withHeader("Content-Type","application/json")
                .withBody("{\"message\":\"order is already filled\"}")));
        var r = provider.cancel("filled");
        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectReason()).contains("already filled");
        assertThat(r.rejectCode()).isEqualTo("422");
    }

    // ---- probe ----

    @Test
    void probe_200_returnsQuietly() {
        wm.stubFor(get(urlEqualTo("/clock")).willReturn(okJson("""
            {"is_open":false}
            """)));

        assertThatCode(() -> provider.probe()).doesNotThrowAnyException();
    }

    @Test
    void probe_401_throwsBrokerException() {
        wm.stubFor(get(urlEqualTo("/clock")).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> provider.probe())
                .isInstanceOf(BrokerException.class);
    }
}
