package de.visterion.agora.tools;

import de.visterion.agora.trading.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class GetOrdersToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private GetOrdersTool tool(BrokerProvider p) { return new GetOrdersTool(TestConnections.service(p)); }

    @Test void namespaceIsTrading() {
        assertThat(tool(new StubBroker()).namespace()).isEqualTo("trading");
    }

    @Test void emptyOrdersShape() {
        var r = tool(new StubBroker()).call(mapper.createObjectNode().put("connection", TestConnections.CONN));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("orders").isArray()).isTrue();
        assertThat(r.output().get("orders").size()).isEqualTo(0);
    }

    @Test void ordersListedCorrectly() {
        var stub = new StubBroker() {
            public List<Order> orders(String status) {
                return List.of(new Order("oid-1", "ref-1", "AAPL", "buy",
                        new BigDecimal("5"), "limit", "new"));
            }
        };
        var r = tool(stub).call(mapper.createObjectNode().put("connection", TestConnections.CONN));
        assertThat(r.available()).isTrue();
        var orders = r.output().get("orders");
        assertThat(orders.size()).isEqualTo(1);
        assertThat(orders.get(0).get("brokerOrderId").asString()).isEqualTo("oid-1");
        assertThat(orders.get(0).get("clientRef").asString()).isEqualTo("ref-1");
        assertThat(orders.get(0).get("symbol").asString()).isEqualTo("AAPL");
        assertThat(orders.get(0).get("side").asString()).isEqualTo("buy");
        assertThat(orders.get(0).get("status").asString()).isEqualTo("new");
    }

    @Test void statusArgPassedToProvider() {
        var captured = new String[1];
        var stub = new StubBroker() {
            public List<Order> orders(String status) {
                captured[0] = status;
                return List.of();
            }
        };
        var args = new ObjectMapper().createObjectNode().put("connection", TestConnections.CONN).put("status", "all");
        tool(stub).call(args);
        assertThat(captured[0]).isEqualTo("all");
    }

    @Test void noStatusArgPassesNull() {
        var captured = new String[]{"not-set"};
        var stub = new StubBroker() {
            public List<Order> orders(String status) {
                captured[0] = status;
                return List.of();
            }
        };
        tool(stub).call(new ObjectMapper().createObjectNode().put("connection", TestConnections.CONN));
        assertThat(captured[0]).isNull();
    }

    @Test void unavailableOnBrokerException() {
        var stub = new StubBroker() {
            public List<Order> orders(String status) {
                throw new BrokerException(BrokerException.Kind.UNAVAILABLE, "down", null);
            }
        };
        var r = tool(stub).call(mapper.createObjectNode().put("connection", TestConnections.CONN));
        assertThat(r.available()).isFalse();
    }

    @Test void missingConnectionUnavailable() {
        var r = tool(new StubBroker()).call(mapper.createObjectNode().put("status", "all"));
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("connection");
    }

    static class StubBroker implements BrokerProvider {
        public String name(){return "stub";}
        public OrderResult submitBracket(BracketOrderRequest r){return OrderResult.accepted("oid",r.clientRef(),"accepted");}
        public OrderResult modifyBracket(String id,BigDecimal s,BigDecimal t){return OrderResult.accepted(id,null,"replaced");}
        public OrderResult flatten(String sym, BigDecimal fraction, BigDecimal qty){return OrderResult.accepted("oid",null,"accepted");}
        public List<Position> positions(){return List.of();}
        public List<Order> orders(String status){return List.of();}
        public Account account(){return new Account("acc",BigDecimal.TEN,BigDecimal.TEN,BigDecimal.TEN,"USD","ACTIVE");}
        public Order orderByClientRef(String ref){return new Order("oid",ref,"AAPL","buy",BigDecimal.ONE,"limit","new");}
        public OrderResult cancel(String brokerOrderId){return OrderResult.accepted(brokerOrderId,null,"canceled");}
        public void probe(){}
    }
}
