package de.visterion.agora.tools;

import de.visterion.agora.trading.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ModifyBracketToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private ModifyBracketTool tool(BrokerProvider p) { return new ModifyBracketTool(TestConnections.service(p)); }

    private StubBroker accepting() {
        return new StubBroker() {
            public OrderResult modifyBracket(String id, String symbol, BigDecimal s, BigDecimal t) {
                return OrderResult.accepted(id, null, "replaced");
            }
        };
    }

    @Test void namespaceIsTrading() { assertThat(tool(accepting()).namespace()).isEqualTo("trading"); }

    @Test void acceptedShape() {
        var args = mapper.createObjectNode().put("connection", TestConnections.CONN).put("orderId","oid-1").put("symbol","AAPL").put("stop",95).put("target",110);
        var r = tool(accepting()).call(args);
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("accepted").asBoolean()).isTrue();
        assertThat(r.output().get("orderId").asString()).isEqualTo("oid-1");
    }

    @Test void rejectedShape() {
        var stub = new StubBroker() {
            public OrderResult modifyBracket(String id, String symbol, BigDecimal s, BigDecimal t) {
                return OrderResult.rejected("order not modifiable", "422");
            }
        };
        // Must provide at least one of stop/target so the empty-adjustment guard does not fire
        var r = tool(stub).call(mapper.createObjectNode().put("connection", TestConnections.CONN).put("orderId","oid-1").put("symbol","AAPL").put("stop", 95));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("accepted").asBoolean()).isFalse();
        assertThat(r.output().get("rejectReason").asString()).contains("not modifiable");
    }

    @Test void unavailableOnBrokerException() {
        var stub = new StubBroker() {
            public OrderResult modifyBracket(String id, String symbol, BigDecimal s, BigDecimal t) {
                throw new BrokerException(BrokerException.Kind.UNAVAILABLE, "down", null);
            }
        };
        // Provide stop so the guard passes and we reach the broker
        var r = tool(stub).call(mapper.createObjectNode().put("connection", TestConnections.CONN).put("orderId","oid-1").put("symbol","AAPL").put("stop", 95));
        assertThat(r.available()).isFalse();
    }

    @Test void symbolRequired() {
        var r = tool(accepting()).call(mapper.createObjectNode()
                .put("connection", TestConnections.CONN).put("orderId", "oid-1").put("stop", 95));
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("symbol");
    }

    @Test void symbolPassedThrough() {
        var seen = new String[1];
        var stub = new StubBroker() {
            public OrderResult modifyBracket(String id, String symbol, BigDecimal s, BigDecimal t) {
                seen[0] = symbol; return OrderResult.accepted(id, null, "replaced");
            }
        };
        var r = tool(stub).call(mapper.createObjectNode()
                .put("connection", TestConnections.CONN).put("orderId", "oid-1").put("symbol", "AAPL").put("stop", 95));
        assertThat(r.available()).isTrue();
        assertThat(seen[0]).isEqualTo("AAPL");
    }

    @Test void unavailableOnMissingOrderId() {
        var r = tool(accepting()).call(mapper.createObjectNode().put("connection", TestConnections.CONN).put("stop", 95));
        assertThat(r.available()).isFalse();
    }

    @Test void unavailableWhenNeitherStopNorTargetProvided() {
        // orderId present but no stop/target → must return unavailable before calling broker
        var r = tool(accepting()).call(mapper.createObjectNode().put("connection", TestConnections.CONN).put("orderId", "oid-1"));
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("stop");
        assertThat(r.error()).contains("target");
    }

    @Test void missingConnectionUnavailable() {
        var r = tool(accepting()).call(mapper.createObjectNode().put("orderId","oid-1").put("stop",95).put("target",110));
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("connection");
    }

    @Test void malformedStopIsUnavailableAndBrokerNeverCalled() {
        BrokerProvider broker = mock(BrokerProvider.class);
        var r = tool(broker).call(mapper.createObjectNode()
                .put("connection", TestConnections.CONN).put("orderId", "oid-1").put("symbol", "AAPL").put("stop", "oops"));
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("invalid numeric argument: stop");
        verify(broker, never()).modifyBracket(any(), any(), any(), any());
    }

    @Test void negativeStopIsUnavailableAndBrokerNeverCalled() {
        BrokerProvider broker = mock(BrokerProvider.class);
        var r = tool(broker).call(mapper.createObjectNode()
                .put("connection", TestConnections.CONN).put("orderId", "oid-1").put("symbol", "AAPL").put("stop", -5));
        assertThat(r.available()).isFalse();
        verify(broker, never()).modifyBracket(any(), any(), any(), any());
    }

    @Test void zeroTargetIsUnavailableAndBrokerNeverCalled() {
        BrokerProvider broker = mock(BrokerProvider.class);
        var r = tool(broker).call(mapper.createObjectNode()
                .put("connection", TestConnections.CONN).put("orderId", "oid-1").put("symbol", "AAPL").put("target", 0));
        assertThat(r.available()).isFalse();
        verify(broker, never()).modifyBracket(any(), any(), any(), any());
    }

    // ---- explicit leg addressing (two-tranche positions) ----

    @Test void schemaExposesOptionalLegIds() {
        var schema = tool(accepting()).inputSchema();
        var props = schema.get("properties");
        assertThat(props.has("stopOrderId")).isTrue();
        assertThat(props.has("targetOrderId")).isTrue();
        // Optional: naming a leg must never become mandatory for existing callers.
        var required = schema.get("required").toString();
        assertThat(required).doesNotContain("stopOrderId").doesNotContain("targetOrderId");
        // The description has to let a caller tell WHICH stop it is moving.
        assertThat(props.get("stopOrderId").get("description").asString()).containsIgnoringCase("stop");
        assertThat(tool(accepting()).description()).containsIgnoringCase("leg");
    }

    @Test void legIdsPassedThroughToBroker() {
        var seen = new String[2];
        var stub = new StubBroker() {
            public OrderResult modifyBracket(String id, String symbol, BigDecimal s, BigDecimal t,
                                             String stopOrderId, String targetOrderId) {
                seen[0] = stopOrderId; seen[1] = targetOrderId;
                return OrderResult.accepted(id, null, "replaced");
            }
        };
        var r = tool(stub).call(mapper.createObjectNode()
                .put("connection", TestConnections.CONN).put("orderId", "oid-1").put("symbol", "IMAX")
                .put("stop", 46.10).put("stopOrderId", "stop-t2"));
        assertThat(r.available()).isTrue();
        assertThat(seen[0]).isEqualTo("stop-t2");
        assertThat(seen[1]).isNull();
    }

    @Test void omittingLegIdsKeepsTodaysBehaviour() {
        var seen = new String[2];
        var stub = new StubBroker() {
            public OrderResult modifyBracket(String id, String symbol, BigDecimal s, BigDecimal t,
                                             String stopOrderId, String targetOrderId) {
                seen[0] = stopOrderId; seen[1] = targetOrderId;
                return OrderResult.accepted(id, null, "replaced");
            }
        };
        tool(stub).call(mapper.createObjectNode()
                .put("connection", TestConnections.CONN).put("orderId", "oid-1").put("symbol", "IMAX").put("stop", 46.10));
        assertThat(seen[0]).isNull();
        assertThat(seen[1]).isNull();
    }

    @Test void providerThatCannotAddressALegSaysSoInsteadOfGuessing() {
        // The interface default: a provider that never overrode the leg-aware overload must
        // reject, not silently modify whichever leg it found.
        var r = tool(new StubBroker()).call(mapper.createObjectNode()
                .put("connection", TestConnections.CONN).put("orderId", "oid-1").put("symbol", "IMAX")
                .put("stop", 46.10).put("stopOrderId", "stop-t2"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("accepted").asBoolean()).isFalse();
        assertThat(r.output().get("rejectCode").asString()).isEqualTo("LEG_ADDRESSING_UNSUPPORTED");
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
