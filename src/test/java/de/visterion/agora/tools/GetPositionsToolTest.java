package de.visterion.agora.tools;

import de.visterion.agora.trading.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class GetPositionsToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private GetPositionsTool tool(BrokerProvider p) { return new GetPositionsTool(TestConnections.service(p)); }

    @Test void namespaceIsTrading() {
        assertThat(tool(new StubBroker()).namespace()).isEqualTo("trading");
    }

    @Test void emptyPositionsShape() {
        var r = tool(new StubBroker()).call(mapper.createObjectNode().put("connection", TestConnections.CONN));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("positions").isArray()).isTrue();
        assertThat(r.output().get("positions").size()).isEqualTo(0);
    }

    @Test void positionsListedCorrectly() {
        var stub = new StubBroker() {
            public List<Position> positions() {
                return List.of(new Position("AAPL", "PriceSmart Inc", new BigDecimal("10"),
                        new BigDecimal("150.00"), new BigDecimal("151.00"), new BigDecimal("1510.00"),
                        new BigDecimal("100.00"), "USD", "Stock", "2026-07-10", 1));
            }
        };
        var r = tool(stub).call(mapper.createObjectNode().put("connection", TestConnections.CONN));
        assertThat(r.available()).isTrue();
        var positions = r.output().get("positions");
        assertThat(positions.size()).isEqualTo(1);
        assertThat(positions.get(0).get("symbol").asString()).isEqualTo("AAPL");
        assertThat(positions.get(0).get("description").asString()).isEqualTo("PriceSmart Inc");
        assertThat(positions.get(0).get("qty").decimalValue()).isEqualByComparingTo("10");
        assertThat(positions.get(0).get("avgEntryPrice").decimalValue()).isEqualByComparingTo("150.00");
        assertThat(positions.get(0).get("marketPrice").decimalValue()).isEqualByComparingTo("151.00");
        assertThat(positions.get(0).get("marketValue").decimalValue()).isEqualByComparingTo("1510.00");
        assertThat(positions.get(0).get("currency").asString()).isEqualTo("USD");
        assertThat(positions.get(0).get("assetType").asString()).isEqualTo("Stock");
        assertThat(positions.get(0).get("valueDate").asString()).isEqualTo("2026-07-10");
        assertThat(positions.get(0).get("openOrdersCount").asInt()).isEqualTo(1);
        assertThat(Instant.parse(r.output().get("asOf").asString())).isNotNull();
    }

    @Test void nullMarketPriceSerializedAsNull() {
        var stub = new StubBroker() {
            public List<Position> positions() {
                return List.of(new Position("AAPL", null, BigDecimal.ZERO, new BigDecimal("150.00"),
                        null, new BigDecimal("0"), BigDecimal.ZERO, "USD", "Stock", null, 0));
            }
        };
        var r = tool(stub).call(mapper.createObjectNode().put("connection", TestConnections.CONN));
        var p0 = r.output().get("positions").get(0);
        assertThat(p0.get("marketPrice").isNull()).isTrue();
        assertThat(p0.get("openOrdersCount").asInt()).isEqualTo(0);
    }

    @Test void unavailableOnBrokerException() {
        var stub = new StubBroker() {
            public List<Position> positions() {
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
