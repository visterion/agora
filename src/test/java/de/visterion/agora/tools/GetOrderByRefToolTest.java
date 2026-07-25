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

    // An unknown ref is the answer "no, there is none" -- not an outage. Until 2026-07-25 it came
    // back as unavailable; Dracul could not tell it apart from "broker down" and therefore blocked
    // every tranche-2 placement for signals with an error history.
    @Test void unknownClientRefIsANegativeResultNotAnOutage() {
        var stub = new StubBroker() {
            public Order orderByClientRef(String ref) {
                throw new BrokerException(BrokerException.Kind.NOT_FOUND, "Order not found: t2-x", null);
            }
        };
        var r = tool(stub).call(mapper.createObjectNode().put("connection", TestConnections.CONN).put("clientRef","t2-x"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().has("order")).isTrue();
        assertThat(r.output().get("order").isNull()).isTrue();
    }

    @Test void aRealOutageStaysUnavailable() {
        var stub = new StubBroker() {
            public Order orderByClientRef(String ref) {
                throw new BrokerException(BrokerException.Kind.UNAVAILABLE, "saxo auth failed", null);
            }
        };
        var r = tool(stub).call(mapper.createObjectNode().put("connection", TestConnections.CONN).put("clientRef","my-ref"));
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("saxo auth failed");
    }

    @Test void notReadyStaysUnavailable() {
        var stub = new StubBroker() {
            public Order orderByClientRef(String ref) {
                throw new BrokerException(BrokerException.Kind.NOT_READY, "saxo rate limited", null);
            }
        };
        var r = tool(stub).call(mapper.createObjectNode().put("connection", TestConnections.CONN).put("clientRef","my-ref"));
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("saxo rate limited");
    }

    @Test void aFoundOrderIsUnchanged() {
        var stub = new StubBroker() {
            public Order orderByClientRef(String ref) {
                return new Order("oid-9", ref, "MSFT", "buy", new BigDecimal("5"), "limit", "working");
            }
        };
        var r = tool(stub).call(mapper.createObjectNode().put("connection", TestConnections.CONN).put("clientRef","my-ref"));
        assertThat(r.available()).isTrue();
        var order = r.output().get("order");
        assertThat(order.isNull()).isFalse();
        assertThat(order.get("brokerOrderId").asString()).isEqualTo("oid-9");
        assertThat(order.get("clientRef").asString()).isEqualTo("my-ref");
        assertThat(order.get("symbol").asString()).isEqualTo("MSFT");
        assertThat(order.get("status").asString()).isEqualTo("working");
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
        public OrderResult modifyBracket(String id,String symbol,BigDecimal s,BigDecimal t){return OrderResult.accepted(id,null,"replaced");}
        public OrderResult flatten(String sym, BigDecimal fraction, BigDecimal qty){return OrderResult.accepted("oid",null,"accepted");}
        public List<Position> positions(){return List.of();}
        public List<ClosedPosition> closedPositions(){return List.of();}
        public List<Order> orders(String status){return List.of();}
        public Account account(){return new Account("acc",BigDecimal.TEN,BigDecimal.TEN,BigDecimal.TEN,"USD","ACTIVE");}
        public Order orderByClientRef(String ref){return new Order("oid",ref,"AAPL","buy",BigDecimal.ONE,"limit","new");}
        public OrderResult cancel(String brokerOrderId){return OrderResult.accepted(brokerOrderId,null,"canceled");}
        public void probe(){}
    }
}
