package de.visterion.agora.tools;

import de.visterion.agora.trading.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PlaceProtectiveStopToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private PlaceProtectiveStopTool tool(BrokerProvider p) { return new PlaceProtectiveStopTool(TestConnections.service(p)); }

    private StubBroker accepting() {
        return new StubBroker() {
            public OrderResult placeProtectiveStop(String sym, BigDecimal qty, BigDecimal stopPrice) {
                return OrderResult.accepted("oid-stop", null, "accepted");
            }
        };
    }

    @Test void namespaceIsTrading() { assertThat(tool(accepting()).namespace()).isEqualTo("trading"); }

    @Test void acceptedShape() {
        var r = tool(accepting()).call(mapper.createObjectNode().put("connection", TestConnections.CONN)
                .put("symbol", "AAPL").put("qty", 34).put("stop_price", 45.49));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("accepted").asBoolean()).isTrue();
        assertThat(r.output().get("orderId").asString()).isEqualTo("oid-stop");
    }

    @Test void argumentsPassedThrough() {
        var capturedSymbol = new String[1];
        var capturedQty = new BigDecimal[1];
        var capturedStop = new BigDecimal[1];
        var stub = new StubBroker() {
            public OrderResult placeProtectiveStop(String sym, BigDecimal qty, BigDecimal stopPrice) {
                capturedSymbol[0] = sym;
                capturedQty[0] = qty;
                capturedStop[0] = stopPrice;
                return OrderResult.accepted("oid", null, "accepted");
            }
        };
        tool(stub).call(mapper.createObjectNode().put("connection", TestConnections.CONN)
                .put("symbol", "IMAX").put("qty", 34).put("stop_price", 45.49));
        assertThat(capturedSymbol[0]).isEqualTo("IMAX");
        assertThat(capturedQty[0]).isEqualByComparingTo("34");
        assertThat(capturedStop[0]).isEqualByComparingTo("45.49");
    }

    @Test void rejectedShape() {
        var stub = new StubBroker() {
            public OrderResult placeProtectiveStop(String sym, BigDecimal qty, BigDecimal stopPrice) {
                return OrderResult.rejected("requested qty 50 exceeds position 46", "QTY_EXCEEDS_POSITION");
            }
        };
        var r = tool(stub).call(mapper.createObjectNode().put("connection", TestConnections.CONN)
                .put("symbol", "AAPL").put("qty", 50).put("stop_price", 45.49));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("accepted").asBoolean()).isFalse();
        assertThat(r.output().get("rejectCode").asString()).isEqualTo("QTY_EXCEEDS_POSITION");
    }

    @Test void qtyZeroOrNegativeIsUnavailableAndBrokerNeverCalled() {
        BrokerProvider broker = mock(BrokerProvider.class);
        var r = tool(broker).call(mapper.createObjectNode().put("connection", TestConnections.CONN)
                .put("symbol", "AAPL").put("qty", 0).put("stop_price", 45.49));
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("qty");
        verify(broker, never()).placeProtectiveStop(any(), any(), any());
    }

    @Test void negativeQtyIsUnavailableAndBrokerNeverCalled() {
        BrokerProvider broker = mock(BrokerProvider.class);
        var r = tool(broker).call(mapper.createObjectNode().put("connection", TestConnections.CONN)
                .put("symbol", "AAPL").put("qty", -5).put("stop_price", 45.49));
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("qty");
        verify(broker, never()).placeProtectiveStop(any(), any(), any());
    }

    @Test void stopPriceZeroOrNegativeIsUnavailableAndBrokerNeverCalled() {
        BrokerProvider broker = mock(BrokerProvider.class);
        var r = tool(broker).call(mapper.createObjectNode().put("connection", TestConnections.CONN)
                .put("symbol", "AAPL").put("qty", 10).put("stop_price", 0));
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("stop_price");
        verify(broker, never()).placeProtectiveStop(any(), any(), any());
    }

    @Test void missingSymbolIsUnavailable() {
        var r = tool(accepting()).call(mapper.createObjectNode().put("connection", TestConnections.CONN)
                .put("qty", 10).put("stop_price", 45.49));
        assertThat(r.available()).isFalse();
    }

    @Test void missingConnectionUnavailable() {
        var r = tool(accepting()).call(mapper.createObjectNode().put("symbol", "AAPL")
                .put("qty", 10).put("stop_price", 45.49));
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("connection");
    }

    @Test void missingQtyIsUnavailableAndBrokerNeverCalled() {
        BrokerProvider broker = mock(BrokerProvider.class);
        var r = tool(broker).call(mapper.createObjectNode().put("connection", TestConnections.CONN)
                .put("symbol", "AAPL").put("stop_price", 45.49));
        assertThat(r.available()).isFalse();
        verify(broker, never()).placeProtectiveStop(any(), any(), any());
    }

    @Test void missingStopPriceIsUnavailableAndBrokerNeverCalled() {
        BrokerProvider broker = mock(BrokerProvider.class);
        var r = tool(broker).call(mapper.createObjectNode().put("connection", TestConnections.CONN)
                .put("symbol", "AAPL").put("qty", 10));
        assertThat(r.available()).isFalse();
        verify(broker, never()).placeProtectiveStop(any(), any(), any());
    }

    @Test void unavailableOnBrokerException() {
        var stub = new StubBroker() {
            public OrderResult placeProtectiveStop(String sym, BigDecimal qty, BigDecimal stopPrice) {
                throw new BrokerException(BrokerException.Kind.NOT_FOUND, "no open position: AAPL", null);
            }
        };
        var r = tool(stub).call(mapper.createObjectNode().put("connection", TestConnections.CONN)
                .put("symbol", "AAPL").put("qty", 10).put("stop_price", 45.49));
        assertThat(r.available()).isFalse();
    }

    static class StubBroker implements BrokerProvider {
        public String name(){return "stub";}
        public OrderResult submitBracket(BracketOrderRequest r){return OrderResult.accepted("oid",r.clientRef(),"accepted");}
        public OrderResult modifyBracket(String id,String symbol,BigDecimal s,BigDecimal t){return OrderResult.accepted(id,null,"replaced");}
        public OrderResult flatten(String sym, BigDecimal fraction, BigDecimal qty){return OrderResult.accepted("oid",null,"accepted");}
        public OrderResult placeProtectiveStop(String sym, BigDecimal qty, BigDecimal stopPrice){return OrderResult.accepted("oid",null,"accepted");}
        public List<Position> positions(){return List.of();}
        public List<ClosedPosition> closedPositions(){return List.of();}
        public List<Order> orders(String status){return List.of();}
        public Account account(){return new Account("acc",BigDecimal.TEN,BigDecimal.TEN,BigDecimal.TEN,"USD","ACTIVE");}
        public Order orderByClientRef(String ref){return new Order("oid",ref,"AAPL","buy",BigDecimal.ONE,"limit","new");}
        public OrderResult cancel(String brokerOrderId){return OrderResult.accepted(brokerOrderId,null,"canceled");}
        public void probe(){}
    }
}
