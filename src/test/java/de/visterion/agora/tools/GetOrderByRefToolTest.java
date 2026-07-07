package de.visterion.agora.tools;

import de.visterion.agora.trading.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class GetOrderByRefToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private GetOrderByRefTool tool(BrokerProvider p) { return new GetOrderByRefTool(TestConnections.service(p)); }

    @Test void namespaceIsTrading() {
        assertThat(tool(new StubBroker()).namespace()).isEqualTo("trading");
    }

    @Test void orderShape() {
        var stub = new StubBroker() {
            public Order orderByClientRef(String ref) {
                return new Order("oid-1", ref, "AAPL", "buy", new BigDecimal("3"), "limit", "filled");
            }
        };
        var r = tool(stub).call(mapper.createObjectNode().put("connection", TestConnections.CONN).put("clientRef","my-ref"));
        assertThat(r.available()).isTrue();
        var order = r.output().get("order");
        assertThat(order).isNotNull();
        assertThat(order.get("brokerOrderId").asString()).isEqualTo("oid-1");
        assertThat(order.get("clientRef").asString()).isEqualTo("my-ref");
        assertThat(order.get("symbol").asString()).isEqualTo("AAPL");
        assertThat(order.get("side").asString()).isEqualTo("buy");
        assertThat(order.get("status").asString()).isEqualTo("filled");
    }

    @Test void unavailableOnNotFound() {
        var stub = new StubBroker() {
            public Order orderByClientRef(String ref) {
                throw new BrokerException(BrokerException.Kind.NOT_FOUND, "not found", null);
            }
        };
        var r = tool(stub).call(mapper.createObjectNode().put("connection", TestConnections.CONN).put("clientRef","my-ref"));
        assertThat(r.available()).isFalse();
    }

    @Test void unavailableOnBrokerException() {
        var stub = new StubBroker() {
            public Order orderByClientRef(String ref) {
                throw new BrokerException(BrokerException.Kind.UNAVAILABLE, "down", null);
            }
        };
        var r = tool(stub).call(mapper.createObjectNode().put("connection", TestConnections.CONN).put("clientRef","my-ref"));
        assertThat(r.available()).isFalse();
    }

    @Test void unavailableOnMissingClientRef() {
        var r = tool(new StubBroker()).call(mapper.createObjectNode().put("connection", TestConnections.CONN));
        assertThat(r.available()).isFalse();
    }

    @Test void missingConnectionUnavailable() {
        var r = tool(new StubBroker()).call(mapper.createObjectNode().put("clientRef","my-ref"));
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("connection");
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
        public void probe(){}
    }
}
