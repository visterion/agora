package de.visterion.agora.tools;

import de.visterion.agora.trading.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class FlattenToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private FlattenTool tool(BrokerProvider p) { return new FlattenTool(new BrokerService(p)); }

    private StubBroker accepting() {
        return new StubBroker() {
            public OrderResult flatten(String sym) {
                return OrderResult.accepted("oid-flat", null, "accepted");
            }
        };
    }

    @Test void namespaceIsTrading() { assertThat(tool(accepting()).namespace()).isEqualTo("trading"); }

    @Test void acceptedShape() {
        var r = tool(accepting()).call(mapper.createObjectNode().put("symbol","AAPL"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("accepted").asBoolean()).isTrue();
        assertThat(r.output().get("orderId").asString()).isEqualTo("oid-flat");
    }

    @Test void rejectedShape() {
        var stub = new StubBroker() {
            public OrderResult flatten(String sym) {
                return OrderResult.rejected("no position found", "422");
            }
        };
        var r = tool(stub).call(mapper.createObjectNode().put("symbol","AAPL"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("accepted").asBoolean()).isFalse();
        assertThat(r.output().get("rejectReason").asString()).contains("position");
    }

    @Test void unavailableOnBrokerException() {
        var stub = new StubBroker() {
            public OrderResult flatten(String sym) {
                throw new BrokerException(BrokerException.Kind.UNAVAILABLE, "down", null);
            }
        };
        var r = tool(stub).call(mapper.createObjectNode().put("symbol","AAPL"));
        assertThat(r.available()).isFalse();
    }

    @Test void unavailableOnMissingSymbol() {
        var r = tool(accepting()).call(mapper.createObjectNode());
        assertThat(r.available()).isFalse();
    }

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
    }
}
