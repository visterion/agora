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

    @Test
    void submitBracket_nullClientRef_bodyHasNoClientOrderIdKey() {
        // M-T5: a null clientRef must not be serialized as an explicit "client_order_id":null —
        // that erases the only idempotency key.
        wm.stubFor(post(urlEqualTo("/orders"))
                .willReturn(okJson("""
                    {"id":"oid-1","status":"accepted"}
                    """)));

        var req = new BracketOrderRequest(
                "AAPL", "buy", new BigDecimal("10"), "limit", "gtc",
                new BigDecimal("190"), new BigDecimal("185"), null,
                new BigDecimal("200"), null);

        provider.submitBracket(req);

        wm.verify(postRequestedFor(urlEqualTo("/orders"))
                .withRequestBody(notMatching(".*client_order_id.*")));
    }

    @Test
    void submitBracket_transportFailureWithClientRef_messageHintsAtRetrySafety() {
        // M-T5: on a transport-level submit failure with a clientRef supplied, the exception
        // message must hint at checking via get_order_by_ref before retrying.
        wm.stubFor(post(urlEqualTo("/orders"))
                .willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        var req = new BracketOrderRequest(
                "AAPL", "buy", new BigDecimal("10"), "limit", "gtc",
                new BigDecimal("190"), new BigDecimal("185"), null,
                new BigDecimal("200"), "ref-idempotent");

        assertThatThrownBy(() -> provider.submitBracket(req))
                .isInstanceOfSatisfying(BrokerException.class, ex ->
                        assertThat(ex.getMessage()).contains("order may already exist")
                                .contains("get_order_by_ref"));
    }

    @Test
    void submitBracket_transportFailureNoClientRef_messageHasNoRetryHint() {
        wm.stubFor(post(urlEqualTo("/orders"))
                .willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        var req = new BracketOrderRequest(
                "AAPL", "buy", new BigDecimal("10"), "limit", "gtc",
                new BigDecimal("190"), new BigDecimal("185"), null,
                new BigDecimal("200"), null);

        assertThatThrownBy(() -> provider.submitBracket(req))
                .isInstanceOfSatisfying(BrokerException.class, ex ->
                        assertThat(ex.getMessage()).doesNotContain("order may already exist"));
    }

    // ---- modifyBracket ----

    @Test
    void modifyBracket_parentLookup_patchesEachLegWithOwnField() {
        wm.stubFor(get(urlPathEqualTo("/orders/par-1"))
                .willReturn(okJson("""
                    {"id":"par-1","symbol":"AAPL","legs":[
                      {"id":"sl-1","type":"stop"},
                      {"id":"tp-1","type":"limit"}]}""")));
        wm.stubFor(patch(urlEqualTo("/orders/sl-1")).willReturn(okJson("{\"id\":\"sl-1\",\"status\":\"replaced\"}")));
        wm.stubFor(patch(urlEqualTo("/orders/tp-1")).willReturn(okJson("{\"id\":\"tp-1\",\"status\":\"replaced\"}")));

        var r = provider.modifyBracket("par-1", "AAPL", new BigDecimal("183"), new BigDecimal("202"));

        assertThat(r.accepted()).isTrue();
        wm.verify(patchRequestedFor(urlEqualTo("/orders/sl-1"))
                .withRequestBody(matchingJsonPath("$.stop_price", equalTo("183")))
                .withRequestBody(notMatching(".*limit_price.*")));
        wm.verify(patchRequestedFor(urlEqualTo("/orders/tp-1"))
                .withRequestBody(matchingJsonPath("$.limit_price", equalTo("202")))
                .withRequestBody(notMatching(".*stop_price.*")));
    }

    @Test
    void modifyBracket_stopOnly_noStopLeg_rejectsLegNotFound() {
        wm.stubFor(get(urlPathEqualTo("/orders/par-2"))
                .willReturn(okJson("{\"id\":\"par-2\",\"symbol\":\"AAPL\",\"legs\":[{\"id\":\"tp-2\",\"type\":\"limit\"}]}")));
        var r = provider.modifyBracket("par-2", "AAPL", new BigDecimal("183"), null);
        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectCode()).isEqualTo("LEG_NOT_FOUND");
    }

    @Test
    void modifyBracket_parentGone_fallsBackToSymbol_unambiguousSingleStop() {
        // C5 (fixed): unambiguous case — exactly one detached bracket/oco stop candidate,
        // no other order carrying its own legs[] on the symbol. Fallback must request nested=true.
        wm.stubFor(get(urlPathEqualTo("/orders/par-x")).willReturn(aResponse().withStatus(404)));
        wm.stubFor(get(urlPathEqualTo("/orders"))
                .withQueryParam("symbols", equalTo("AAPL"))
                .withQueryParam("nested", equalTo("true"))
                .willReturn(okJson("""
                    [{"id":"sl-9","type":"stop","order_class":"bracket","symbol":"AAPL","status":"held"}]""")));
        wm.stubFor(patch(urlEqualTo("/orders/sl-9")).willReturn(okJson("{\"id\":\"sl-9\",\"status\":\"replaced\"}")));

        var r = provider.modifyBracket("par-x", "AAPL", new BigDecimal("180"), null);
        assertThat(r.accepted()).isTrue();
        wm.verify(patchRequestedFor(urlEqualTo("/orders/sl-9"))
                .withRequestBody(matchingJsonPath("$.stop_price", equalTo("180"))));
    }

    @Test
    void modifyBracket_parentGone_fallsBackToSymbol_excludesEntryWithOwnLegs_zeroTpCandidatesLegNotFound() {
        // C5: two open orders for the symbol — bracket-B's still-unfilled entry (type limit,
        // carries its own non-empty legs[] => never a protective leg) and a detached stop
        // belonging to gone bracket A. The stop is unambiguous and gets patched; the target
        // modify has ZERO take-profit candidates (bracket-B's entry is excluded, not "multiple")
        // — this must reject as LEG_NOT_FOUND, not AMBIGUOUS_LEGS (zero candidates is not the
        // same condition as more-than-one). Bracket-B's entry limit price must never be touched.
        wm.stubFor(get(urlPathEqualTo("/orders/par-a")).willReturn(aResponse().withStatus(404)));
        wm.stubFor(get(urlPathEqualTo("/orders"))
                .withQueryParam("symbols", equalTo("AAPL"))
                .withQueryParam("nested", equalTo("true"))
                .willReturn(okJson("""
                    [{"id":"entry-b","type":"limit","order_class":"bracket","symbol":"AAPL","status":"new",
                      "legs":[{"id":"sl-b","type":"stop"},{"id":"tp-b","type":"limit"}]},
                     {"id":"sl-a","type":"stop","order_class":"bracket","symbol":"AAPL","status":"held"}]""")));
        wm.stubFor(patch(urlEqualTo("/orders/sl-a")).willReturn(okJson("{\"id\":\"sl-a\",\"status\":\"replaced\"}")));

        var r = provider.modifyBracket("par-a", "AAPL", new BigDecimal("180"), new BigDecimal("210"));

        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectCode()).isEqualTo("LEG_NOT_FOUND");
        assertThat(r.rejectReason()).contains("stop-loss was already moved");
        wm.verify(patchRequestedFor(urlEqualTo("/orders/sl-a"))
                .withRequestBody(matchingJsonPath("$.stop_price", equalTo("180"))));
        wm.verify(0, patchRequestedFor(urlEqualTo("/orders/entry-b")));
    }

    @Test
    void modifyBracket_parentGone_fallsBackToSymbol_oneStopOneTp_bothPatchSuccessfully() {
        // Happy-path fallback: exactly one detached stop candidate and exactly one detached
        // take-profit (limit) candidate for the symbol — both legs resolve unambiguously and
        // both patches succeed.
        wm.stubFor(get(urlPathEqualTo("/orders/par-y")).willReturn(aResponse().withStatus(404)));
        wm.stubFor(get(urlPathEqualTo("/orders"))
                .withQueryParam("symbols", equalTo("AAPL"))
                .withQueryParam("nested", equalTo("true"))
                .willReturn(okJson("""
                    [{"id":"sl-y","type":"stop","order_class":"bracket","symbol":"AAPL","status":"held"},
                     {"id":"tp-y","type":"limit","order_class":"bracket","symbol":"AAPL","status":"held"}]""")));
        wm.stubFor(patch(urlEqualTo("/orders/sl-y")).willReturn(okJson("{\"id\":\"sl-y\",\"status\":\"replaced\"}")));
        wm.stubFor(patch(urlEqualTo("/orders/tp-y")).willReturn(okJson("{\"id\":\"tp-y\",\"status\":\"replaced\"}")));

        var r = provider.modifyBracket("par-y", "AAPL", new BigDecimal("180"), new BigDecimal("210"));

        assertThat(r.accepted()).isTrue();
        wm.verify(patchRequestedFor(urlEqualTo("/orders/sl-y"))
                .withRequestBody(matchingJsonPath("$.stop_price", equalTo("180"))));
        wm.verify(patchRequestedFor(urlEqualTo("/orders/tp-y"))
                .withRequestBody(matchingJsonPath("$.limit_price", equalTo("210"))));
    }

    @Test
    void modifyBracket_parentGone_fallsBackToSymbol_twoDetachedStops_ambiguousRefused_noPatch() {
        // C5-ambiguous: two candidate stop-loss orders for the symbol — cannot uniquely
        // resolve which one belongs to the (gone) bracket. Must refuse without patching either.
        wm.stubFor(get(urlPathEqualTo("/orders/par-x")).willReturn(aResponse().withStatus(404)));
        wm.stubFor(get(urlPathEqualTo("/orders"))
                .withQueryParam("symbols", equalTo("AAPL"))
                .withQueryParam("nested", equalTo("true"))
                .willReturn(okJson("""
                    [{"id":"sl-9","type":"stop","order_class":"bracket","symbol":"AAPL","status":"held"},
                     {"id":"sl-10","type":"stop","order_class":"bracket","symbol":"AAPL","status":"held"}]""")));

        var r = provider.modifyBracket("par-x", "AAPL", new BigDecimal("180"), null);

        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectCode()).isEqualTo("AMBIGUOUS_LEGS");
        assertThat(r.rejectReason()).contains("cannot uniquely resolve bracket legs for AAPL");
        wm.verify(0, patchRequestedFor(urlPathMatching("/orders/sl-.*")));
    }

    @Test
    void modifyBracket_slPatchSucceeds_tpPatchFails_rejectionMentionsAlreadyMovedStop() {
        // M-T1: non-atomic modify — SL patch 200, TP patch 422 → rejection must state the
        // stop-loss was already moved (order stays SL-then-TP).
        wm.stubFor(get(urlPathEqualTo("/orders/par-1"))
                .willReturn(okJson("""
                    {"id":"par-1","symbol":"AAPL","legs":[
                      {"id":"sl-1","type":"stop"},
                      {"id":"tp-1","type":"limit"}]}""")));
        wm.stubFor(patch(urlEqualTo("/orders/sl-1")).willReturn(okJson("{\"id\":\"sl-1\",\"status\":\"replaced\"}")));
        wm.stubFor(patch(urlEqualTo("/orders/tp-1"))
                .willReturn(aResponse().withStatus(422).withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"take profit too close to market\"}")));

        var r = provider.modifyBracket("par-1", "AAPL", new BigDecimal("183"), new BigDecimal("202"));

        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectReason()).contains("stop-loss was already moved");
        assertThat(r.rejectReason()).contains("take profit too close to market");
    }

    @Test
    void modifyBracket_parentLookupConnectionFault_throwsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/orders/par-1"))
                .willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> provider.modifyBracket("par-1", "AAPL", new BigDecimal("180"), null))
                .isInstanceOfSatisfying(BrokerException.class, ex ->
                        assertThat(ex.kind()).isEqualTo(BrokerException.Kind.UNAVAILABLE));
    }

    // ---- flatten ----

    @Test
    void flatten_200_returnsAccepted() {
        wm.stubFor(get(urlPathEqualTo("/positions/AAPL"))
                .willReturn(okJson("{\"symbol\":\"AAPL\",\"qty\":\"10\"}")));
        wm.stubFor(delete(urlEqualTo("/positions/AAPL"))
                .willReturn(okJson("""
                    {"id":"oid-9","client_order_id":null,"status":"accepted"}
                    """)));

        var result = provider.flatten("AAPL", null, null);

        assertThat(result.accepted()).isTrue();
    }

    @Test
    void flatten_422_returnsRejected_notThrows() {
        wm.stubFor(get(urlPathEqualTo("/positions/AAPL"))
                .willReturn(okJson("{\"symbol\":\"AAPL\",\"qty\":\"10\"}")));
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
        wm.stubFor(get(urlPathEqualTo("/positions/GOOG"))
                .willReturn(okJson("{\"symbol\":\"GOOG\",\"qty\":\"10\"}")));
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
        // M-T3: position qty is now fetched BEFORE the DELETE; a 404 on that pre-fetch means
        // "no open position" — the DELETE is never even attempted.
        wm.stubFor(get(urlPathEqualTo("/positions/ZZZZ")).willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> provider.flatten("ZZZZ", null, null))
                .isInstanceOfSatisfying(BrokerException.class, ex -> {
                    assertThat(ex.kind()).isEqualTo(BrokerException.Kind.NOT_FOUND);
                    assertThat(ex.getMessage()).contains("no open position");
                });
        wm.verify(0, deleteRequestedFor(urlPathEqualTo("/positions/ZZZZ")));
    }

    @Test
    void flatten_withQty_sendsQtyQueryParam() {
        wm.stubFor(get(urlPathEqualTo("/positions/AAPL"))
                .willReturn(okJson("{\"symbol\":\"AAPL\",\"qty\":\"10\"}")));
        wm.stubFor(delete(urlPathEqualTo("/positions/AAPL"))
                .withQueryParam("qty", equalTo("3"))
                .willReturn(okJson("""
                    {"id":"oid-9","qty":"3","status":"accepted"}
                    """)));

        var result = provider.flatten("AAPL", null, new BigDecimal("3"));

        assertThat(result.accepted()).isTrue();
        assertThat(result.closedQty()).isEqualByComparingTo("3");
        assertThat(result.remainingQty()).isEqualByComparingTo("7");
        wm.verify(deleteRequestedFor(urlPathEqualTo("/positions/AAPL")).withQueryParam("qty", equalTo("3")));
    }

    @Test
    void flatten_withFraction_sendsPercentageQueryParam() {
        wm.stubFor(get(urlPathEqualTo("/positions/AAPL"))
                .willReturn(okJson("{\"symbol\":\"AAPL\",\"qty\":\"10\"}")));
        wm.stubFor(delete(urlPathEqualTo("/positions/AAPL"))
                .withQueryParam("percentage", equalTo("50"))
                .willReturn(okJson("""
                    {"id":"oid-9","qty":"5","status":"accepted"}
                    """)));

        var result = provider.flatten("AAPL", new BigDecimal("0.5"), null);

        assertThat(result.accepted()).isTrue();
        assertThat(result.closedQty()).isEqualByComparingTo("5");
        assertThat(result.remainingQty()).isEqualByComparingTo("5");
        wm.verify(deleteRequestedFor(urlPathEqualTo("/positions/AAPL")).withQueryParam("percentage", equalTo("50")));
    }

    @Test
    void flatten_parsesFilledAvgPriceWhenPresent() {
        wm.stubFor(get(urlPathEqualTo("/positions/AAPL"))
                .willReturn(okJson("{\"symbol\":\"AAPL\",\"qty\":\"3\"}")));
        wm.stubFor(delete(urlPathEqualTo("/positions/AAPL"))
                .willReturn(okJson("""
                    {"id":"oid-9","qty":"3","filled_avg_price":"101.50","status":"filled"}
                    """)));

        var result = provider.flatten("AAPL", null, new BigDecimal("3"));

        assertThat(result.avgFillPrice()).isEqualByComparingTo("101.50");
        assertThat(result.remainingQty()).isEqualByComparingTo("0");
    }

    @Test
    void flatten_remainingQtyIsPreQtyMinusClosedQty() {
        // M-T3: pre-position qty 10, close qty 4 -> remainingQty 6 (not a racy post-close read).
        wm.stubFor(get(urlPathEqualTo("/positions/AAPL"))
                .willReturn(okJson("{\"symbol\":\"AAPL\",\"qty\":\"10\"}")));
        wm.stubFor(delete(urlPathEqualTo("/positions/AAPL"))
                .willReturn(okJson("""
                    {"id":"cls-1","qty":"4","status":"accepted"}
                    """)));

        var r = provider.flatten("AAPL", null, new BigDecimal("4"));

        assertThat(r.remainingQty()).isEqualByComparingTo("6");
        // the position endpoint must only be hit once, BEFORE the delete (no post-close read).
        wm.verify(1, getRequestedFor(urlPathEqualTo("/positions/AAPL")));
    }

    @Test
    void flatten_fullClose_remainingQtyZero() {
        wm.stubFor(get(urlPathEqualTo("/positions/AAPL"))
                .willReturn(okJson("{\"symbol\":\"AAPL\",\"qty\":\"10\"}")));
        wm.stubFor(delete(urlPathEqualTo("/positions/AAPL"))
                .willReturn(okJson("""
                    {"id":"cls-2","qty":"10","status":"accepted"}
                    """)));

        var r = provider.flatten("AAPL", null, null);

        assertThat(r.remainingQty()).isEqualByComparingTo("0");
    }

    @Test
    void flatten_preFetchConnectionFault_throwsUnavailable() {
        // Position qty is now a required pre-condition, not a best-effort follow-up read:
        // a pre-fetch connection failure must fail the close (never fabricate a remaining qty).
        wm.stubFor(get(urlPathEqualTo("/positions/AAPL"))
                .willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> provider.flatten("AAPL", null, new BigDecimal("5")))
                .isInstanceOfSatisfying(BrokerException.class, ex ->
                        assertThat(ex.kind()).isEqualTo(BrokerException.Kind.UNAVAILABLE));
        wm.verify(0, deleteRequestedFor(urlPathEqualTo("/positions/AAPL")));
    }

    @Test
    void flatten_blankSymbol_throwsIllegalArgument_beforeAnyHttp() {
        assertThatThrownBy(() -> provider.flatten("", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        wm.verify(0, getRequestedFor(urlPathMatching("/positions/.*")));
        wm.verify(0, deleteRequestedFor(urlPathMatching("/positions/.*")));
    }

    // ---- positions() ----

    @Test
    void positions_parsesListCorrectly() {
        wm.stubFor(get(urlEqualTo("/positions"))
                .willReturn(okJson("""
                    [
                      {"symbol":"AAPL","qty":"10","avg_entry_price":"185.50",
                       "market_value":"1900.00","unrealized_pl":"145.00","asset_class":"us_equity",
                       "current_price":"151.25"}
                    ]
                    """)));

        var positions = provider.positions();

        assertThat(positions).hasSize(1);
        var p = positions.get(0);
        assertThat(p.symbol()).isEqualTo("AAPL");
        assertThat(p.description()).isNull();
        assertThat(p.qty()).isEqualByComparingTo("10");
        assertThat(p.avgEntryPrice()).isEqualByComparingTo("185.50");
        assertThat(p.marketPrice()).isEqualByComparingTo("151.25");
        assertThat(p.marketValue()).isEqualByComparingTo("1900.00");
        assertThat(p.unrealizedPl()).isEqualByComparingTo("145.00");
        assertThat(p.currency()).isEqualTo("USD");
        assertThat(p.assetType()).isEqualTo("us_equity");
        assertThat(p.valueDate()).isNull();
        assertThat(p.openOrdersCount()).isEqualTo(0);
    }

    @Test
    void positions_assetTypeNullWhenAssetClassAbsent() {
        wm.stubFor(get(urlEqualTo("/positions"))
                .willReturn(okJson("""
                    [
                      {"symbol":"AAPL","qty":"10","avg_entry_price":"185.50",
                       "market_value":"1900.00","unrealized_pl":"145.00"}
                    ]
                    """)));

        var positions = provider.positions();

        assertThat(positions).hasSize(1);
        assertThat(positions.get(0).assetType()).isNull();
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
        // M-T4: a top-level bracket/oco order is only "entry" when it still carries its own
        // (non-empty) legs[] — the realistic shape of an unfilled bracket parent.
        wm.stubFor(get(urlPathEqualTo("/orders"))
                .willReturn(okJson("""
                    [
                      {"id":"oid-10","client_order_id":"ref-10","symbol":"MSFT",
                       "side":"buy","qty":"3","order_type":"limit","status":"new",
                       "order_class":"bracket",
                       "legs":[{"id":"leg-stop","type":"stop"},{"id":"leg-limit","type":"limit"}]}
                    ]
                    """)));

        var orders = provider.orders(null);

        assertThat(orders.get(0).role()).isEqualTo("entry");
    }

    @Test
    void orders_bracketTopLevelNoLegs_roleDerivedFromType_notEntry() {
        // M-T4: an orphaned bracket leg fetched/listed standalone (no legs[] of its own) must
        // NOT be mislabeled "entry" just because it carries the parent's order_class.
        wm.stubFor(get(urlPathEqualTo("/orders"))
                .willReturn(okJson("""
                    [
                      {"id":"leg-stop","client_order_id":"ref-11","symbol":"MSFT",
                       "side":"sell","qty":"3","order_type":"stop","status":"new",
                       "order_class":"bracket"}
                    ]
                    """)));

        var orders = provider.orders(null);

        assertThat(orders.get(0).role()).isEqualTo("stop_loss");
        assertThat(orders.get(0).parentId()).isNull();
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

    @Test
    void orders_paginatesWithKeysetAfterUntilShortPage() {
        // M-T2: limit=500, direction=asc; loop with after=<submitted_at of last> until a page
        // returns fewer than 500 rows. First page: 500 rows (submitted_at 000..499); second
        // page: 3 rows. Total orders() output: 503 (no legs in this fixture).
        StringBuilder page1 = new StringBuilder("[");
        for (int i = 0; i < 500; i++) {
            if (i > 0) page1.append(",");
            page1.append(String.format("""
                {"id":"oid-%d","symbol":"AAPL","side":"buy","qty":"1","order_type":"limit",
                 "status":"new","submitted_at":"2026-01-01T00:00:%02d.000Z"}""", i, i % 60));
        }
        page1.append("]");

        wm.stubFor(get(urlPathEqualTo("/orders"))
                .withQueryParam("limit", equalTo("500"))
                .withQueryParam("direction", equalTo("asc"))
                .inScenario("paging").whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willSetStateTo("page2")
                .willReturn(okJson(page1.toString())));
        wm.stubFor(get(urlPathEqualTo("/orders"))
                .withQueryParam("limit", equalTo("500"))
                .withQueryParam("direction", equalTo("asc"))
                .withQueryParam("after", equalTo("2026-01-01T00:00:19.000Z"))
                .inScenario("paging").whenScenarioStateIs("page2")
                .willReturn(okJson("""
                    [{"id":"oid-500","symbol":"AAPL","side":"buy","qty":"1","order_type":"limit",
                      "status":"new","submitted_at":"2026-01-01T00:01:00.000Z"},
                     {"id":"oid-501","symbol":"AAPL","side":"buy","qty":"1","order_type":"limit",
                      "status":"new","submitted_at":"2026-01-01T00:01:01.000Z"},
                     {"id":"oid-502","symbol":"AAPL","side":"buy","qty":"1","order_type":"limit",
                      "status":"new","submitted_at":"2026-01-01T00:01:02.000Z"}]
                    """)));

        var orders = provider.orders(null);

        assertThat(orders).hasSize(503);
        wm.verify(2, getRequestedFor(urlPathEqualTo("/orders")));
    }

    @Test
    void orders_invalidQuery422_throwsUnavailableWithClearMessage() {
        wm.stubFor(get(urlPathEqualTo("/orders"))
                .willReturn(aResponse().withStatus(422)));

        assertThatThrownBy(() -> provider.orders("bogus-status"))
                .isInstanceOfSatisfying(BrokerException.class, ex -> {
                    assertThat(ex.kind()).isEqualTo(BrokerException.Kind.UNAVAILABLE);
                    assertThat(ex.getMessage()).contains("invalid order query");
                });
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
    void cancel_blankOrderId_throwsIllegalArgument_beforeAnyHttp() {
        assertThatThrownBy(() -> provider.cancel(" "))
                .isInstanceOf(IllegalArgumentException.class);
        wm.verify(0, deleteRequestedFor(urlPathMatching("/orders/.*")));
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

    @Test
    void slowBrokerResponseFailsFastAsUnavailable() {
        wm.stubFor(post(urlEqualTo("/orders"))
                .willReturn(okJson("{\"id\":\"oid-1\",\"status\":\"accepted\"}").withFixedDelay(3_000)));
        var fastTimeoutProvider = new AlpacaBrokerProvider(wm.baseUrl(), "k", "s", 250L);
        var req = new BracketOrderRequest(
                "AAPL", "buy", new BigDecimal("10"), "limit", "gtc",
                new BigDecimal("190"), new BigDecimal("185"), null,
                new BigDecimal("200"), "ref-1");
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> fastTimeoutProvider.submitBracket(req))
                .isInstanceOfSatisfying(BrokerException.class,
                        e -> assertThat(e.kind()).isEqualTo(BrokerException.Kind.UNAVAILABLE));
        assertThat((System.nanoTime() - t0) / 1_000_000L).isLessThan(2_500L);
    }
}
