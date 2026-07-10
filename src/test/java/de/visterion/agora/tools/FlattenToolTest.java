package de.visterion.agora.tools;

import de.visterion.agora.trading.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FlattenToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private FlattenTool tool(BrokerProvider p) { return new FlattenTool(TestConnections.service(p)); }

    private StubBroker accepting() {
        return new StubBroker() {
            public OrderResult flatten(String sym, BigDecimal fraction, BigDecimal qty) {
                return OrderResult.accepted("oid-flat", null, "accepted");
            }
        };
    }

    @Test void namespaceIsTrading() { assertThat(tool(accepting()).namespace()).isEqualTo("trading"); }

    @Test void acceptedShape() {
        var r = tool(accepting()).call(mapper.createObjectNode().put("connection", TestConnections.CONN).put("symbol","AAPL"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("accepted").asBoolean()).isTrue();
        assertThat(r.output().get("orderId").asString()).isEqualTo("oid-flat");
    }

    @Test void defaultCallPassesNullFractionAndQty() {
        var captured = new BigDecimal[2];
        var stub = new StubBroker() {
            public OrderResult flatten(String sym, BigDecimal fraction, BigDecimal qty) {
                captured[0] = fraction;
                captured[1] = qty;
                return OrderResult.accepted("oid", null, "accepted");
            }
        };
        tool(stub).call(mapper.createObjectNode().put("connection", TestConnections.CONN).put("symbol","AAPL"));
        assertThat(captured[0]).isNull();
        assertThat(captured[1]).isNull();
    }

    @Test void fractionPassedThrough() {
        var captured = new BigDecimal[1];
        var stub = new StubBroker() {
            public OrderResult flatten(String sym, BigDecimal fraction, BigDecimal qty) {
                captured[0] = fraction;
                return OrderResult.accepted("oid", null, "accepted");
            }
        };
        tool(stub).call(mapper.createObjectNode().put("connection", TestConnections.CONN).put("symbol","AAPL").put("fraction", 0.5));
        assertThat(captured[0]).isEqualByComparingTo("0.5");
    }

    @Test void qtyPassedThrough() {
        var captured = new BigDecimal[1];
        var stub = new StubBroker() {
            public OrderResult flatten(String sym, BigDecimal fraction, BigDecimal qty) {
                captured[0] = qty;
                return OrderResult.accepted("oid", null, "accepted");
            }
        };
        tool(stub).call(mapper.createObjectNode().put("connection", TestConnections.CONN).put("symbol","AAPL").put("qty", 3));
        assertThat(captured[0]).isEqualByComparingTo("3");
    }

    @Test void fractionAndQtyTogetherIsUnavailable() {
        var r = tool(accepting()).call(mapper.createObjectNode().put("connection", TestConnections.CONN)
                .put("symbol","AAPL").put("fraction", 0.5).put("qty", 3));
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("mutually exclusive");
    }

    @Test void fractionZeroIsUnavailable() {
        var r = tool(accepting()).call(mapper.createObjectNode().put("connection", TestConnections.CONN)
                .put("symbol","AAPL").put("fraction", 0));
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("fraction");
    }

    @Test void fractionAboveOneIsUnavailable() {
        var r = tool(accepting()).call(mapper.createObjectNode().put("connection", TestConnections.CONN)
                .put("symbol","AAPL").put("fraction", 1.5));
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("fraction");
    }

    @Test void fractionExactlyOneIsAllowed() {
        var r = tool(accepting()).call(mapper.createObjectNode().put("connection", TestConnections.CONN)
                .put("symbol","AAPL").put("fraction", 1.0));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("accepted").asBoolean()).isTrue();
    }

    @Test void qtyZeroOrNegativeIsUnavailable() {
        var r = tool(accepting()).call(mapper.createObjectNode().put("connection", TestConnections.CONN)
                .put("symbol","AAPL").put("qty", 0));
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("qty");
    }

    @Test void acceptedShapeIncludesFillDetailWhenPresent() {
        var stub = new StubBroker() {
            public OrderResult flatten(String sym, BigDecimal fraction, BigDecimal qty) {
                return OrderResult.accepted("oid-flat", null, "accepted",
                        new BigDecimal("3"), new BigDecimal("7"), new BigDecimal("101.50"));
            }
        };
        var r = tool(stub).call(mapper.createObjectNode().put("connection", TestConnections.CONN).put("symbol","AAPL").put("qty", 3));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("closedQty").decimalValue()).isEqualByComparingTo("3");
        assertThat(r.output().get("remainingQty").decimalValue()).isEqualByComparingTo("7");
        assertThat(r.output().get("avgFillPrice").decimalValue()).isEqualByComparingTo("101.50");
    }

    @Test void acceptedShapeOmitsFillDetailWhenAbsent() {
        var r = tool(accepting()).call(mapper.createObjectNode().put("connection", TestConnections.CONN).put("symbol","AAPL"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().has("closedQty")).isFalse();
        assertThat(r.output().has("remainingQty")).isFalse();
        assertThat(r.output().has("avgFillPrice")).isFalse();
    }

    @Test void rejectedShape() {
        var stub = new StubBroker() {
            public OrderResult flatten(String sym, BigDecimal fraction, BigDecimal qty) {
                return OrderResult.rejected("no position found", "422");
            }
        };
        var r = tool(stub).call(mapper.createObjectNode().put("connection", TestConnections.CONN).put("symbol","AAPL"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("accepted").asBoolean()).isFalse();
        assertThat(r.output().get("rejectReason").asString()).contains("position");
    }

    @Test void unavailableOnBrokerException() {
        var stub = new StubBroker() {
            public OrderResult flatten(String sym, BigDecimal fraction, BigDecimal qty) {
                throw new BrokerException(BrokerException.Kind.UNAVAILABLE, "down", null);
            }
        };
        var r = tool(stub).call(mapper.createObjectNode().put("connection", TestConnections.CONN).put("symbol","AAPL"));
        assertThat(r.available()).isFalse();
    }

    @Test void unavailableOnMissingSymbol() {
        var r = tool(accepting()).call(mapper.createObjectNode().put("connection", TestConnections.CONN));
        assertThat(r.available()).isFalse();
    }

    @Test void missingConnectionUnavailable() {
        var r = tool(accepting()).call(mapper.createObjectNode().put("symbol","AAPL"));
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("connection");
    }

    @Test void blankSymbolIsUnavailableAndBrokerNeverCalled() {
        BrokerProvider broker = mock(BrokerProvider.class);
        var r = tool(broker).call(mapper.createObjectNode().put("connection", TestConnections.CONN).put("symbol", ""));
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("symbol");
        verify(broker, never()).flatten(any(), any(), any());
    }

    @Test void whitespaceOnlySymbolIsUnavailableAndBrokerNeverCalled() {
        BrokerProvider broker = mock(BrokerProvider.class);
        var r = tool(broker).call(mapper.createObjectNode().put("connection", TestConnections.CONN).put("symbol", "   "));
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("symbol");
        verify(broker, never()).flatten(any(), any(), any());
    }

    @Test void qtyAsNumericStringParsesToBigDecimal() {
        var captured = new BigDecimal[1];
        var stub = new StubBroker() {
            public OrderResult flatten(String sym, BigDecimal fraction, BigDecimal qty) {
                captured[0] = qty;
                return OrderResult.accepted("oid", null, "accepted");
            }
        };
        var r = tool(stub).call(mapper.createObjectNode().put("connection", TestConnections.CONN).put("symbol","AAPL").put("qty", "5"));
        assertThat(r.available()).isTrue();
        assertThat(captured[0]).isEqualByComparingTo(new BigDecimal("5"));
    }

    @Test void qtyAsUnparsableStringIsUnavailableAndBrokerNeverCalled() {
        BrokerProvider broker = mock(BrokerProvider.class);
        var r = tool(broker).call(mapper.createObjectNode().put("connection", TestConnections.CONN).put("symbol","AAPL").put("qty", "abc"));
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("invalid numeric argument: qty");
        verify(broker, never()).flatten(any(), any(), any());
    }

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
