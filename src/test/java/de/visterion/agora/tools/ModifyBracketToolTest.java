package de.visterion.agora.tools;

import de.visterion.agora.trading.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ModifyBracketToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private ModifyBracketTool tool(BrokerProvider p) { return new ModifyBracketTool(new BrokerService(p)); }

    private StubBroker accepting() {
        return new StubBroker() {
            public OrderResult modifyBracket(String id, BigDecimal s, BigDecimal t) {
                return OrderResult.accepted(id, null, "replaced");
            }
        };
    }

    @Test void namespaceIsTrading() { assertThat(tool(accepting()).namespace()).isEqualTo("trading"); }

    @Test void acceptedShape() {
        var args = mapper.createObjectNode().put("orderId","oid-1").put("stop",95).put("target",110);
        var r = tool(accepting()).call(args);
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("accepted").asBoolean()).isTrue();
        assertThat(r.output().get("orderId").asString()).isEqualTo("oid-1");
    }

    @Test void rejectedShape() {
        var stub = new StubBroker() {
            public OrderResult modifyBracket(String id, BigDecimal s, BigDecimal t) {
                return OrderResult.rejected("order not modifiable", "422");
            }
        };
        var r = tool(stub).call(mapper.createObjectNode().put("orderId","oid-1"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("accepted").asBoolean()).isFalse();
        assertThat(r.output().get("rejectReason").asString()).contains("not modifiable");
    }

    @Test void unavailableOnBrokerException() {
        var stub = new StubBroker() {
            public OrderResult modifyBracket(String id, BigDecimal s, BigDecimal t) {
                throw new BrokerException(BrokerException.Kind.UNAVAILABLE, "down", null);
            }
        };
        var r = tool(stub).call(mapper.createObjectNode().put("orderId","oid-1"));
        assertThat(r.available()).isFalse();
    }

    @Test void unavailableOnMissingOrderId() {
        var r = tool(accepting()).call(mapper.createObjectNode().put("stop", 95));
        assertThat(r.available()).isFalse();
    }

    static class StubBroker implements BrokerProvider {
        public String name(){return "stub";}
        public OrderResult submitBracket(BracketOrderRequest r){return OrderResult.accepted("oid",r.clientRef(),"accepted");}
        public OrderResult modifyBracket(String id,BigDecimal s,BigDecimal t){return OrderResult.accepted(id,null,"replaced");}
        public OrderResult flatten(String sym){return OrderResult.accepted("oid",null,"accepted");}
        public List<Position> positions(){return List.of();}
        public List<Order> orders(){return List.of();}
        public Account account(){return new Account("acc",BigDecimal.TEN,BigDecimal.TEN,BigDecimal.TEN,"USD","ACTIVE");}
        public Order orderByClientRef(String ref){return new Order("oid",ref,"AAPL","buy",BigDecimal.ONE,"limit","new");}
    }
}
