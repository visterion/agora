package de.visterion.agora.trading;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class BrokerServiceTest {

    private static BrokerProvider stub(String accountId) {
        return new BrokerProvider() {
            public String name() { return "stub"; }
            public OrderResult submitBracket(BracketOrderRequest r) { return OrderResult.accepted("oid-1", r.clientRef(), "accepted"); }
            public OrderResult modifyBracket(String id, BigDecimal s, BigDecimal t) { return OrderResult.accepted(id, null, "replaced"); }
            public OrderResult flatten(String sym) { return OrderResult.accepted("oid-2", null, "accepted"); }
            public List<Position> positions() { return List.of(); }
            public List<Order> orders(String status) { return List.of(); }
            public Account account() { return new Account(accountId, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, "USD", "ACTIVE"); }
            public Order orderByClientRef(String ref) { return new Order("oid-1", ref, "AAPL", "buy", BigDecimal.ONE, "limit", "new"); }
            public OrderResult cancel(String id) { return OrderResult.accepted(id, null, "canceled"); }
            public void probe() {}
        };
    }

    private static ConnectionConfig cfg(ConnectionConfig.Environment env) {
        ConnectionConfig c = new ConnectionConfig();
        c.setProvider("stub");
        c.setEnvironment(env);
        c.setKeyId("k");
        c.setSecret("s");
        return c;
    }

    /** Registry with paper connection "paper-1" (account acc-P) and live connection "live-1" (account acc-L). */
    private static ConnectionRegistry twoConnRegistry() {
        Map<String, ConnectionConfig> conns = new LinkedHashMap<>();
        conns.put("paper-1", cfg(ConnectionConfig.Environment.PAPER));
        conns.put("live-1", cfg(ConnectionConfig.Environment.LIVE));
        ConnectionsProperties props = new ConnectionsProperties();
        props.setConnections(conns);
        BrokerProviderFactory f = new BrokerProviderFactory() {
            public String provider() { return "stub"; }
            public BrokerProvider create(ConnectionConfig cfg) {
                return stub(cfg.getEnvironment() == ConnectionConfig.Environment.LIVE ? "acc-L" : "acc-P");
            }
        };
        return new ConnectionRegistry(props, List.of(f));
    }

    private static LiveAccessGuard guard(String presentedToken) {
        return new LiveAccessGuard(Set.of("live-token"), () -> presentedToken);
    }

    @Test
    void routesToTheNamedConnection() {
        BrokerService svc = new BrokerService(twoConnRegistry(), guard("live-token"));
        assertThat(svc.account("paper-1").accountId()).isEqualTo("acc-P");
        assertThat(svc.account("live-1").accountId()).isEqualTo("acc-L");
    }

    @Test
    void unknownConnectionListsOnlyVisibleIds() {
        BrokerService svc = new BrokerService(twoConnRegistry(), guard("trade-token"));
        assertThatThrownBy(() -> svc.positions("nope"))
                .isInstanceOf(BrokerException.class)
                .hasMessageContaining("unknown or inactive connection: nope")
                .hasMessageContaining("paper-1")
                .hasMessageNotContaining("live-1");   // live id must NOT leak to a non-live token
    }

    @Test
    void unknownConnectionWithLiveTokenListsAllIds() {
        BrokerService svc = new BrokerService(twoConnRegistry(), guard("live-token"));
        assertThatThrownBy(() -> svc.positions("nope"))
                .isInstanceOf(BrokerException.class)
                .hasMessageContaining("paper-1")
                .hasMessageContaining("live-1");
    }

    @Test
    void liveTargetWithoutLiveTokenIsIndistinguishableFromUnknown() {
        BrokerService svc = new BrokerService(twoConnRegistry(), guard("trade-token"));
        assertThatThrownBy(() -> svc.account("live-1"))
                .isInstanceOf(BrokerException.class)
                .hasMessageContaining("unknown or inactive connection: live-1")
                // active list contains ONLY paper-1 → proves live-1 is not enumerated:
                .hasMessageContaining("(active: paper-1)");
        // No dedicated "live token required" message → no enumeration oracle.
    }

    @Test
    void liveTargetWithLiveTokenRoutes() {
        BrokerService svc = new BrokerService(twoConnRegistry(), guard("live-token"));
        var r = svc.submitBracket("live-1", new BracketOrderRequest("AAPL", "buy", BigDecimal.ONE,
                "limit", "gtc", new BigDecimal("100"), new BigDecimal("95"), null,
                new BigDecimal("110"), "ref-1"));
        assertThat(r.accepted()).isTrue();
    }

    @Test
    void paperTargetWorksWithoutLiveToken() {
        BrokerService svc = new BrokerService(twoConnRegistry(), guard(null));
        assertThat(svc.positions("paper-1")).isEmpty();
    }
}
