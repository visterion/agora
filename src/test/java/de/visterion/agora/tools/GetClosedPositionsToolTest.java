package de.visterion.agora.tools;

import de.visterion.agora.trading.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class GetClosedPositionsToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private GetClosedPositionsTool tool(BrokerProvider p) { return new GetClosedPositionsTool(TestConnections.service(p)); }

    @Test void namespaceIsTrading() {
        assertThat(tool(new StubBroker()).namespace()).isEqualTo("trading");
    }

    @Test void emptyClosedPositionsShape() {
        var r = tool(new StubBroker()).call(mapper.createObjectNode().put("connection", TestConnections.CONN));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("closedPositions").isArray()).isTrue();
        assertThat(r.output().get("closedPositions").size()).isEqualTo(0);
    }

    @Test void closedPositionsListedCorrectly() {
        var stub = new StubBroker() {
            public List<ClosedPosition> closedPositions() {
                return List.of(new ClosedPosition("ISRG", 36313L, new BigDecimal("364.35"),
                        new BigDecimal("364.10"), new BigDecimal("3"), new BigDecimal("-0.25"), "sig-1"));
            }
        };
        var r = tool(stub).call(mapper.createObjectNode().put("connection", TestConnections.CONN));
        assertThat(r.available()).isTrue();
        var closedPositions = r.output().get("closedPositions");
        assertThat(closedPositions.size()).isEqualTo(1);
        var cp = closedPositions.get(0);
        assertThat(cp.get("symbol").asString()).isEqualTo("ISRG");
        assertThat(cp.get("uic").asLong()).isEqualTo(36313L);
        assertThat(cp.get("openPrice").decimalValue()).isEqualByComparingTo("364.35");
        assertThat(cp.get("closePrice").decimalValue()).isEqualByComparingTo("364.10");
        assertThat(cp.get("amount").decimalValue()).isEqualByComparingTo("3");
        assertThat(cp.get("profitLoss").decimalValue()).isEqualByComparingTo("-0.25");
        assertThat(cp.get("clientRef").asString()).isEqualTo("sig-1");
    }

    @Test void clientRefOmittedWhenNull() {
        var stub = new StubBroker() {
            public List<ClosedPosition> closedPositions() {
                return List.of(new ClosedPosition("AAPL", 211L, new BigDecimal("150.00"),
                        new BigDecimal("155.00"), new BigDecimal("10"), new BigDecimal("50.00"), null));
            }
        };
        var r = tool(stub).call(mapper.createObjectNode().put("connection", TestConnections.CONN));
        var cp = r.output().get("closedPositions").get(0);
        assertThat(cp.has("clientRef")).isFalse();
    }

    @Test void unavailableOnBrokerException() {
        var stub = new StubBroker() {
            public List<ClosedPosition> closedPositions() {
                throw new BrokerException(BrokerException.Kind.UNAVAILABLE, "down", null);
            }
        };
        var r = tool(stub).call(mapper.createObjectNode().put("connection", TestConnections.CONN));
        assertThat(r.available()).isFalse();
    }

    @Test void missingConnectionUnavailable() {
        var r = tool(new StubBroker()).call(mapper.createObjectNode());
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("connection");
    }

    @Test void unsupportedBrokerEmitsSupportedFalseAndNoteWithoutCallingProvider() {
        boolean[] called = {false};
        var stub = new StubBroker() {
            @Override public boolean supportsClosedPositions() { return false; }
            @Override public List<ClosedPosition> closedPositions(String from, String to) { called[0] = true; return List.of(); }
        };
        var r = tool(stub).call(mapper.createObjectNode().put("connection", TestConnections.CONN));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("closedPositions").size()).isEqualTo(0);
        assertThat(r.output().get("supported").asBoolean()).isFalse();
        assertThat(r.output().get("note").asString()).contains("get_orders");
        assertThat(called[0]).isFalse();
    }

    @Test void newFieldsEmittedWhenPresent() {
        var stub = new StubBroker() {
            @Override public List<ClosedPosition> closedPositions(String from, String to) {
                return List.of(new ClosedPosition("ISRG",36313L,new BigDecimal("364.35"),
                        new BigDecimal("364.10"),new BigDecimal("3"),new BigDecimal("-0.25"),"sig-1",
                        "2026-07-01T09:00:00Z","2026-07-01T15:30:00Z",998877L));
            }
        };
        var cp = tool(stub).call(mapper.createObjectNode().put("connection", TestConnections.CONN))
                .output().get("closedPositions").get(0);
        assertThat(cp.get("openTime").asString()).isEqualTo("2026-07-01T09:00:00Z");
        assertThat(cp.get("closeTime").asString()).isEqualTo("2026-07-01T15:30:00Z");
        assertThat(cp.get("openingPositionId").asLong()).isEqualTo(998877L);
    }

    @Test void windowLimitedFlagWhenRangeRequested() {
        var stub = new StubBroker() {
            @Override public List<ClosedPosition> closedPositions(String from, String to) { return List.of(); }
        };
        var r = tool(stub).call(mapper.createObjectNode().put("connection", TestConnections.CONN)
                .put("from", "2026-07-01T00:00:00Z"));
        assertThat(r.output().get("windowLimited").asBoolean()).isTrue();
    }

    @Test void noWindowLimitedWhenNoRange() {
        var r = tool(new StubBroker()).call(mapper.createObjectNode().put("connection", TestConnections.CONN));
        assertThat(r.output().has("windowLimited")).isFalse();
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
