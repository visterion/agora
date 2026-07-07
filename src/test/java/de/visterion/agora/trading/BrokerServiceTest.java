package de.visterion.agora.trading;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class BrokerServiceTest {
    private BrokerProvider stub() {
        return new BrokerProvider() {
            public String name() { return "stub"; }
            public OrderResult submitBracket(BracketOrderRequest r) { return OrderResult.accepted("oid-1", r.clientRef(), "accepted"); }
            public OrderResult modifyBracket(String id, BigDecimal s, BigDecimal t) { return OrderResult.accepted(id, null, "replaced"); }
            public OrderResult flatten(String sym) { return OrderResult.accepted("oid-2", null, "accepted"); }
            public List<Position> positions() { return List.of(); }
            public List<Order> orders(String status) { return List.of(); }
            public Account account() { return new Account("acc-1", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, "USD", "ACTIVE"); }
            public Order orderByClientRef(String ref) { return new Order("oid-1", ref, "AAPL", "buy", BigDecimal.ONE, "limit", "new"); }
            public OrderResult cancel(String brokerOrderId) { return OrderResult.accepted(brokerOrderId, null, "canceled"); }
            public void probe() {}
        };
    }

    @Test
    void delegatesToActiveProvider() {
        BrokerService svc = new BrokerService(stub());
        var r = svc.submitBracket(new BracketOrderRequest("AAPL", "buy", BigDecimal.ONE, "limit", "gtc",
                new BigDecimal("100"), new BigDecimal("95"), null, new BigDecimal("110"), "ref-1"));
        assertThat(r.accepted()).isTrue();
        assertThat(r.clientRef()).isEqualTo("ref-1");
        assertThat(svc.account().accountId()).isEqualTo("acc-1");
    }
}
