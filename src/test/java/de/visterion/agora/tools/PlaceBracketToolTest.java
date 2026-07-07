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

    private PlaceBracketTool tool(BrokerProvider p) { return new PlaceBracketTool(new BrokerService(p)); }

    private BrokerProvider accepting() {
        return new StubBroker() {
            public OrderResult submitBracket(BracketOrderRequest r) { return OrderResult.accepted("oid-1", r.clientRef(), "accepted"); }
        };
    }

    @Test void namespaceIsTrading() { assertThat(tool(accepting()).namespace()).isEqualTo("trading"); }

    @Test void acceptedShape() {
        ObjectNode a = mapper.createObjectNode();
        a.put("symbol","AAPL").put("side","buy").put("qty",1).put("stopLossStop",95).put("takeProfitLimit",110).put("clientRef","ref-1");
        var r = tool(accepting()).call(a);
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("accepted").asBoolean()).isTrue();
        assertThat(r.output().get("orderId").asString()).isEqualTo("oid-1");
    }

    @Test void rejectedShape() {
        var r = tool(new StubBroker(){ public OrderResult submitBracket(BracketOrderRequest req){ return OrderResult.rejected("insufficient buying power","403"); }})
                .call(mapper.createObjectNode().put("symbol","AAPL").put("side","buy").put("qty",1).put("stopLossStop",95).put("takeProfitLimit",110));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("accepted").asBoolean()).isFalse();
        assertThat(r.output().get("rejectReason").asString()).contains("buying power");
    }

    @Test void unavailableOnBrokerException() {
        var r = tool(new StubBroker(){ public OrderResult submitBracket(BracketOrderRequest req){ throw new BrokerException(BrokerException.Kind.UNAVAILABLE,"down",null); }})
                .call(mapper.createObjectNode().put("symbol","AAPL").put("side","buy").put("qty",1).put("stopLossStop",95).put("takeProfitLimit",110));
        assertThat(r.available()).isFalse();
    }

    @Test void unavailableOnMissingSymbol() {
        var r = tool(accepting()).call(mapper.createObjectNode().put("side","buy").put("qty",1));
        assertThat(r.available()).isFalse();
    }

    @Test void unavailableOnMissingSide() {
        var r = tool(accepting()).call(mapper.createObjectNode().put("symbol","AAPL").put("qty",1).put("stopLossStop",95).put("takeProfitLimit",110));
        assertThat(r.available()).isFalse();
    }

    @Test void unavailableOnMissingQty() {
        var r = tool(accepting()).call(mapper.createObjectNode().put("symbol","AAPL").put("side","buy").put("stopLossStop",95).put("takeProfitLimit",110));
        assertThat(r.available()).isFalse();
    }

    @Test void unavailableOnMissingStopLossStop() {
        var r = tool(accepting()).call(mapper.createObjectNode().put("symbol","AAPL").put("side","buy").put("qty",1).put("takeProfitLimit",110));
        assertThat(r.available()).isFalse();
    }

    @Test void unavailableOnMissingTakeProfitLimit() {
        var r = tool(accepting()).call(mapper.createObjectNode().put("symbol","AAPL").put("side","buy").put("qty",1).put("stopLossStop",95));
        assertThat(r.available()).isFalse();
    }

    @Test void clientRefPassedThrough() {
        ObjectNode a = mapper.createObjectNode();
        a.put("symbol","AAPL").put("side","buy").put("qty",1).put("stopLossStop",95).put("takeProfitLimit",110).put("clientRef","my-ref");
        var r = tool(accepting()).call(a);
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("clientRef").asString()).isEqualTo("my-ref");
    }

    /** Stub with sensible defaults; tests override one method. */
    static class StubBroker implements BrokerProvider {
        public String name(){return "stub";}
        public OrderResult submitBracket(BracketOrderRequest r){return OrderResult.accepted("oid",r.clientRef(),"accepted");}
        public OrderResult modifyBracket(String id,BigDecimal s,BigDecimal t){return OrderResult.accepted(id,null,"replaced");}
        public OrderResult flatten(String sym){return OrderResult.accepted("oid",null,"accepted");}
        public List<Position> positions(){return List.of();}
        public List<Order> orders(String status){return List.of();}
        public Account account(){return new Account("acc",BigDecimal.TEN,BigDecimal.TEN,BigDecimal.TEN,"USD","ACTIVE");}
        public Order orderByClientRef(String ref){return new Order("oid",ref,"AAPL","buy",BigDecimal.ONE,"limit","new");}
        public OrderResult cancel(String brokerOrderId){return OrderResult.accepted(brokerOrderId,null,"canceled");}
        public void probe(){}
    }
}
