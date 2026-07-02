package de.visterion.agora.tools;

import de.visterion.agora.trading.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class GetAccountToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private GetAccountTool tool(BrokerProvider p) { return new GetAccountTool(new BrokerService(p)); }

    @Test void namespaceIsTrading() {
        assertThat(tool(new StubBroker()).namespace()).isEqualTo("trading");
    }

    @Test void accountShape() {
        var stub = new StubBroker() {
            public Account account() {
                return new Account("acc-42", new BigDecimal("50000"), new BigDecimal("20000"),
                        new BigDecimal("15000"), "USD", "ACTIVE");
            }
        };
        var r = tool(stub).call(mapper.createObjectNode());
        assertThat(r.available()).isTrue();
        var acct = r.output().get("account");
        assertThat(acct).isNotNull();
        assertThat(acct.get("accountId").asString()).isEqualTo("acc-42");
        assertThat(acct.get("equity").decimalValue()).isEqualByComparingTo("50000");
        assertThat(acct.get("buyingPower").decimalValue()).isEqualByComparingTo("20000");
        assertThat(acct.get("cash").decimalValue()).isEqualByComparingTo("15000");
        assertThat(acct.get("currency").asString()).isEqualTo("USD");
        assertThat(acct.get("status").asString()).isEqualTo("ACTIVE");
    }

    @Test void unavailableOnBrokerException() {
        var stub = new StubBroker() {
            public Account account() {
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
