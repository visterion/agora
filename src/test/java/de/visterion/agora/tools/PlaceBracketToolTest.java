package de.visterion.agora.tools;

import de.visterion.agora.trading.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class PlaceBracketToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private PlaceBracketTool tool(BrokerProvider p) { return new PlaceBracketTool(TestConnections.service(p)); }

    private BrokerProvider accepting() {
        return new StubBroker() {
            public OrderResult submitBracket(BracketOrderRequest r) { return OrderResult.accepted("oid-1", r.clientRef(), "accepted"); }
        };
    }

    @Test void namespaceIsTrading() { assertThat(tool(accepting()).namespace()).isEqualTo("trading"); }

    @Test void acceptedShape() {
        ObjectNode a = mapper.createObjectNode();
        a.put("connection", TestConnections.CONN).put("symbol","AAPL").put("side","buy").put("qty",1).put("limitPrice",100).put("stopLossStop",95).put("takeProfitLimit",110).put("clientRef","ref-1");
        var r = tool(accepting()).call(a);
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("accepted").asBoolean()).isTrue();
        assertThat(r.output().get("orderId").asString()).isEqualTo("oid-1");
    }

    /** Task 3: the fallback path (Saxo standalone StopIfTraded, no bracket parent) yields an
     *  OrderResult with a stopLegId but no takeProfitLegId. The tool envelope must be the
     *  SAME shape as a normal bracket result: accepted/orderId/stopLegId populated, and no
     *  crash / no bogus "takeProfitLegId" key when that leg is null. */
    @Test void acceptedShapeUniformForFallbackWithoutTakeProfitLeg() {
        var r = tool(new StubBroker() {
            public OrderResult submitBracket(BracketOrderRequest req) {
                return OrderResult.accepted("entry-id", req.clientRef(), "accepted", "stop-id", null);
            }
        }).call(mapper.createObjectNode()
                .put("connection", TestConnections.CONN).put("symbol","AAPL").put("side","buy")
                .put("qty",1).put("limitPrice",100).put("stopLossStop",95).put("takeProfitLimit",110));

        assertThat(r.available()).isTrue();
        assertThat(r.output().get("accepted").asBoolean()).isTrue();
        assertThat(r.output().get("orderId").asString()).isEqualTo("entry-id");
        assertThat(r.output().get("stopLegId").asString()).isEqualTo("stop-id");
        assertThat(r.output().has("takeProfitLegId")).isFalse();
    }

    @Test void rejectedShape() {
        var r = tool(new StubBroker(){ public OrderResult submitBracket(BracketOrderRequest req){ return OrderResult.rejected("insufficient buying power","403"); }})
                .call(mapper.createObjectNode().put("connection", TestConnections.CONN).put("symbol","AAPL").put("side","buy").put("qty",1).put("limitPrice",100).put("stopLossStop",95).put("takeProfitLimit",110));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("accepted").asBoolean()).isFalse();
        assertThat(r.output().get("rejectReason").asString()).contains("buying power");
    }

    @Test void unavailableOnBrokerException() {
        var r = tool(new StubBroker(){ public OrderResult submitBracket(BracketOrderRequest req){ throw new BrokerException(BrokerException.Kind.UNAVAILABLE,"down",null); }})
                .call(mapper.createObjectNode().put("connection", TestConnections.CONN).put("symbol","AAPL").put("side","buy").put("qty",1).put("limitPrice",100).put("stopLossStop",95).put("takeProfitLimit",110));
        assertThat(r.available()).isFalse();
    }

    @Test void unavailableOnMissingSymbol() {
        var r = tool(accepting()).call(mapper.createObjectNode().put("connection", TestConnections.CONN).put("side","buy").put("qty",1));
        assertThat(r.available()).isFalse();
    }

    @Test void unavailableOnMissingSide() {
        var r = tool(accepting()).call(mapper.createObjectNode().put("connection", TestConnections.CONN).put("symbol","AAPL").put("qty",1).put("stopLossStop",95).put("takeProfitLimit",110));
        assertThat(r.available()).isFalse();
    }

    @Test void unavailableOnMissingQty() {
        var r = tool(accepting()).call(mapper.createObjectNode().put("connection", TestConnections.CONN).put("symbol","AAPL").put("side","buy").put("stopLossStop",95).put("takeProfitLimit",110));
        assertThat(r.available()).isFalse();
    }

    @Test void unavailableOnMissingStopLossStop() {
        var r = tool(accepting()).call(mapper.createObjectNode().put("connection", TestConnections.CONN).put("symbol","AAPL").put("side","buy").put("qty",1).put("takeProfitLimit",110));
        assertThat(r.available()).isFalse();
    }

    @Test void unavailableOnMissingTakeProfitLimit() {
        var r = tool(accepting()).call(mapper.createObjectNode().put("connection", TestConnections.CONN).put("symbol","AAPL").put("side","buy").put("qty",1).put("stopLossStop",95));
        assertThat(r.available()).isFalse();
    }

    @Test void clientRefPassedThrough() {
        ObjectNode a = mapper.createObjectNode();
        a.put("connection", TestConnections.CONN).put("symbol","AAPL").put("side","buy").put("qty",1).put("limitPrice",100).put("stopLossStop",95).put("takeProfitLimit",110).put("clientRef","my-ref");
        var r = tool(accepting()).call(a);
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("clientRef").asString()).isEqualTo("my-ref");
    }

    @Test void missingConnectionUnavailable() {
        var r = tool(accepting()).call(mapper.createObjectNode().put("symbol","AAPL").put("side","buy").put("qty",1).put("limitPrice",100).put("stopLossStop",95).put("takeProfitLimit",110));
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("connection");
    }

    // --- M-X1: side / sign / relational validation ---------------------------------------

    @Test void invalidSideRejected() {
        var r = tool(accepting()).call(mapper.createObjectNode()
                .put("connection", TestConnections.CONN).put("symbol","AAPL").put("side","hold")
                .put("qty",1).put("limitPrice",100).put("stopLossStop",95).put("takeProfitLimit",110));
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("side");
    }

    @Test void nonPositiveQtyRejected() {
        var r = tool(accepting()).call(mapper.createObjectNode()
                .put("connection", TestConnections.CONN).put("symbol","AAPL").put("side","buy")
                .put("qty",0).put("limitPrice",100).put("stopLossStop",95).put("takeProfitLimit",110));
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("qty");
    }

    @Test void negativeLimitPriceRejected() {
        var r = tool(accepting()).call(mapper.createObjectNode()
                .put("connection", TestConnections.CONN).put("symbol","AAPL").put("side","buy")
                .put("qty",1).put("limitPrice",-5).put("stopLossStop",95).put("takeProfitLimit",110));
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("limitPrice");
    }

    @Test void buyWithStopLossAboveEntryRejectedNamingRelation() {
        var r = tool(accepting()).call(mapper.createObjectNode()
                .put("connection", TestConnections.CONN).put("symbol","AAPL").put("side","buy")
                .put("qty",1).put("limitPrice",100).put("stopLossStop",105).put("takeProfitLimit",110));
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("takeProfitLimit").contains("limitPrice").contains("stopLossStop");
    }

    @Test void buyMarketEntryOnlyChecksTakeProfitVsStopLoss() {
        var r = tool(accepting()).call(mapper.createObjectNode()
                .put("connection", TestConnections.CONN).put("symbol","AAPL").put("side","buy").put("type","market")
                .put("qty",1).put("stopLossStop",95).put("takeProfitLimit",110));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("accepted").asBoolean()).isTrue();
    }

    @Test void sellWithInvertedRelationRejected() {
        var r = tool(accepting()).call(mapper.createObjectNode()
                .put("connection", TestConnections.CONN).put("symbol","AAPL").put("side","sell")
                .put("qty",1).put("limitPrice",100).put("stopLossStop",95).put("takeProfitLimit",110));
        assertThat(r.available()).isFalse();
    }

    @Test void validSellReachesBroker() {
        var r = tool(accepting()).call(mapper.createObjectNode()
                .put("connection", TestConnections.CONN).put("symbol","AAPL").put("side","sell")
                .put("qty",1).put("limitPrice",100).put("stopLossStop",105).put("takeProfitLimit",90));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("accepted").asBoolean()).isTrue();
    }

    // --- M-X2: malformed optional numeric args --------------------------------------------

    @Test void malformedStopLossLimitRejectedWithExplicitError() {
        ObjectNode a = mapper.createObjectNode();
        a.put("connection", TestConnections.CONN).put("symbol","AAPL").put("side","buy").put("qty",1)
                .put("limitPrice",100).put("stopLossStop",95).put("takeProfitLimit",110).put("stopLossLimit","x");
        var r = tool(accepting()).call(a);
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("stopLossLimit");
    }

    @Test void malformedLimitPriceRejectedWithExplicitError() {
        ObjectNode a = mapper.createObjectNode();
        a.put("connection", TestConnections.CONN).put("symbol","AAPL").put("side","buy").put("qty",1)
                .put("limitPrice","not-a-number").put("stopLossStop",95).put("takeProfitLimit",110);
        var r = tool(accepting()).call(a);
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("limitPrice");
    }

    // --- M-X3: default type "limit" requires limitPrice -----------------------------------

    @Test void defaultLimitTypeWithoutLimitPriceRejected() {
        var r = tool(accepting()).call(mapper.createObjectNode()
                .put("connection", TestConnections.CONN).put("symbol","AAPL").put("side","buy")
                .put("qty",1).put("stopLossStop",95).put("takeProfitLimit",110));
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("limitPrice");
    }

    @Test void explicitMarketTypeWithoutLimitPriceAccepted() {
        var r = tool(accepting()).call(mapper.createObjectNode()
                .put("connection", TestConnections.CONN).put("symbol","AAPL").put("side","buy").put("type","market")
                .put("qty",1).put("stopLossStop",95).put("takeProfitLimit",110));
        assertThat(r.available()).isTrue();
    }

    /** Stub with sensible defaults; tests override one method. */
    static class StubBroker implements BrokerProvider {
        public String name(){return "stub";}
        public OrderResult submitBracket(BracketOrderRequest r){return OrderResult.accepted("oid",r.clientRef(),"accepted");}
        public OrderResult modifyBracket(String id,String symbol,BigDecimal s,BigDecimal t){return OrderResult.accepted(id,null,"replaced");}
        public OrderResult flatten(String sym, BigDecimal fraction, BigDecimal qty){return OrderResult.accepted("oid",null,"accepted");}
        public List<Position> positions(){return List.of();}
        public List<Order> orders(String status){return List.of();}
        public Account account(){return new Account("acc",BigDecimal.TEN,BigDecimal.TEN,BigDecimal.TEN,"USD","ACTIVE");}
        public Order orderByClientRef(String ref){return new Order("oid",ref,"AAPL","buy",BigDecimal.ONE,"limit","new");}
        public OrderResult cancel(String brokerOrderId){return OrderResult.accepted(brokerOrderId,null,"canceled");}
        public void probe(){}
    }
}
