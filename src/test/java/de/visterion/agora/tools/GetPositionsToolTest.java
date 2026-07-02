package de.visterion.agora.tools;

import de.visterion.agora.trading.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class GetPositionsToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private GetPositionsTool tool(BrokerProvider p) { return new GetPositionsTool(new BrokerService(p)); }

    @Test void namespaceIsTrading() {
        assertThat(tool(new StubBroker()).namespace()).isEqualTo("trading");
    }

    @Test void emptyPositionsShape() {
        var r = tool(new StubBroker()).call(mapper.createObjectNode());
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("positions").isArray()).isTrue();
        assertThat(r.output().get("positions").size()).isEqualTo(0);
    }

    @Test void positionsListedCorrectly() {
        var stub = new StubBroker() {
            public List<Position> positions() {
                return List.of(new Position("AAPL", new BigDecimal("10"), new BigDecimal("150.00"),
                        new BigDecimal("1510.00"), new BigDecimal("100.00"), "USD"));
            }
        };
        var r = tool(stub).call(mapper.createObjectNode());
        assertThat(r.available()).isTrue();
        var positions = r.output().get("positions");
        assertThat(positions.size()).isEqualTo(1);
        assertThat(positions.get(0).get("symbol").asString()).isEqualTo("AAPL");
        assertThat(positions.get(0).get("qty").decimalValue()).isEqualByComparingTo("10");
        assertThat(positions.get(0).get("avgEntryPrice").decimalValue()).isEqualByComparingTo("150.00");
        assertThat(positions.get(0).get("currency").asString()).isEqualTo("USD");
    }

    @Test void unavailableOnBrokerException() {
        var stub = new StubBroker() {
            public List<Position> positions() {
                throw new BrokerException(BrokerException.Kind.UNAVAILABLE, "down", null);
            }
        };
        var r = tool(stub).call(mapper.createObjectNode());
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
