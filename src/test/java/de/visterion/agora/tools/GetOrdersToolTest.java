package de.visterion.agora.tools;

import de.visterion.agora.trading.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
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
        assertThat(Instant.parse(r.output().get("asOf").asString())).isNotNull();
    }

    @Test void nullClientRefOmittedNotExplicitNull() {
        var stub = new StubBroker() {
            public List<Order> orders(String status) {
                return List.of(new Order("oid-1", null, "AAPL", "buy",
                        new BigDecimal("5"), "limit", "new"));
            }
        };
        var r = tool(stub).call(mapper.createObjectNode().put("connection", TestConnections.CONN));
        var order = r.output().get("orders").get(0);
        assertThat(order.has("clientRef")).isFalse();
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

    @Test void fromToPassedToProvider() {
        var captured = new String[2];
        var stub = new StubBroker() {
            @Override public List<Order> orders(String status, String from, String to) {
                captured[0] = from; captured[1] = to; return List.of();
            }
        };
        tool(stub).call(mapper.createObjectNode().put("connection", TestConnections.CONN)
                .put("from", "2026-07-01T00:00:00Z").put("to", "2026-07-02T00:00:00Z"));
        assertThat(captured).containsExactly("2026-07-01T00:00:00Z", "2026-07-02T00:00:00Z");
    }

    @Test void timestampsEmittedWhenPresentOmittedWhenNull() {
        var stub = new StubBroker() {
            @Override public List<Order> orders(String status, String from, String to) {
                return List.of(
                    new Order("o1","r1","AAPL","buy",new BigDecimal("5"),"limit","filled","entry",
                              new BigDecimal("5"),new BigDecimal("150"),null,
                              "2026-07-01T10:00:00Z","2026-07-01T10:00:05Z"),
                    new Order("o2",null,"MSFT","buy",new BigDecimal("1"),"limit","new"));
            }
        };
        var r = tool(stub).call(mapper.createObjectNode().put("connection", TestConnections.CONN));
        var arr = r.output().get("orders");
        assertThat(arr.get(0).get("submittedAt").asString()).isEqualTo("2026-07-01T10:00:00Z");
        assertThat(arr.get(0).get("filledAt").asString()).isEqualTo("2026-07-01T10:00:05Z");
        assertThat(arr.get(1).has("submittedAt")).isFalse();
        assertThat(arr.get(1).has("filledAt")).isFalse();
    }

    @Test void limitAndStopPriceEmittedWhenPresentOmittedWhenNull() {
        var stub = new StubBroker() {
            @Override public List<Order> orders(String status, String from, String to) {
                return List.of(
                    new Order("o1","r1","AAPL","sell",new BigDecimal("5"),"limit","working","take_profit",
                              null,null,new BigDecimal("226.03"),null,"parent-1",
                              null,null),
                    new Order("o2","r2","AAPL","sell",new BigDecimal("5"),"stopiftraded","working","stop_loss",
                              null,null,null,new BigDecimal("168.03"),"parent-1",
                              null,null),
                    new Order("o3",null,"MSFT","buy",new BigDecimal("1"),"market","new"));
            }
        };
        var r = tool(stub).call(mapper.createObjectNode().put("connection", TestConnections.CONN));
        var arr = r.output().get("orders");
        assertThat(arr.get(0).get("limitPrice").decimalValue()).isEqualByComparingTo("226.03");
        assertThat(arr.get(0).has("stopPrice")).isFalse();
        assertThat(arr.get(1).get("stopPrice").decimalValue()).isEqualByComparingTo("168.03");
        assertThat(arr.get(1).has("limitPrice")).isFalse();
        assertThat(arr.get(2).has("limitPrice")).isFalse();
        assertThat(arr.get(2).has("stopPrice")).isFalse();
    }

    @Test void schemaDeclaresFromAndTo() {
        var props = tool(new StubBroker()).inputSchema().get("properties");
        assertThat(props.has("from")).isTrue();
        assertThat(props.has("to")).isTrue();
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
