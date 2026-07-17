package de.visterion.agora.trading;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ConnectionRegistryTest {

    /** Minimal provider stub. */
    private static BrokerProvider dummyProvider() {
        return new BrokerProvider() {
            public String name() { return "stub"; }
            public OrderResult submitBracket(BracketOrderRequest r) { return OrderResult.accepted("o", null, "accepted"); }
            public OrderResult modifyBracket(String id, String symbol, BigDecimal s, BigDecimal t) { return OrderResult.accepted(id, null, "replaced"); }
            public OrderResult flatten(String sym, BigDecimal fraction, BigDecimal qty) { return OrderResult.accepted("o", null, "accepted"); }
            public List<Position> positions() { return List.of(); }
            public List<ClosedPosition> closedPositions() { return List.of(); }
            public List<Order> orders(String status) { return List.of(); }
            public Account account() { return new Account("a", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, "USD", "ACTIVE"); }
            public Order orderByClientRef(String ref) { return new Order("o", ref, "AAPL", "buy", BigDecimal.ONE, "limit", "new"); }
            public OrderResult cancel(String id) { return OrderResult.accepted(id, null, "canceled"); }
            public void probe() {}
        };
    }

    private static BrokerProviderFactory factory(String key) {
        return new BrokerProviderFactory() {
            public String provider() { return key; }
            public BrokerProvider create(String connectionId, ConnectionConfig cfg) { return dummyProvider(); }
        };
    }

    private static ConnectionConfig cfg(String provider, ConnectionConfig.Environment env, String keyId, String secret) {
        ConnectionConfig c = new ConnectionConfig();
        c.setProvider(provider);
        c.setEnvironment(env);
        c.setBaseUrl("http://example");
        c.setKeyId(keyId);
        c.setSecret(secret);
        return c;
    }

    private static ConnectionsProperties props(Map<String, ConnectionConfig> connections) {
        ConnectionsProperties p = new ConnectionsProperties();
        p.setConnections(connections);
        return p;
    }

    @Test
    void activeConnectionIsRegistered() {
        var registry = new ConnectionRegistry(
                props(Map.of("c1", cfg("stub", ConnectionConfig.Environment.PAPER, "k", "s"))),
                List.of(factory("stub")));

        assertThat(registry.get("c1")).isPresent();
        assertThat(registry.active()).hasSize(1);
        var rc = registry.get("c1").orElseThrow();
        assertThat(rc.id()).isEqualTo("c1");
        assertThat(rc.config().getEnvironment()).isEqualTo(ConnectionConfig.Environment.PAPER);
        assertThat(rc.probeStatus().state()).isEqualTo("unknown");
    }

    @Test
    void connectionWithoutCredentialsIsInactive() {
        var registry = new ConnectionRegistry(
                props(Map.of("c1", cfg("stub", ConnectionConfig.Environment.PAPER, "", ""))),
                List.of(factory("stub")));

        assertThat(registry.get("c1")).isEmpty();
        assertThat(registry.active()).isEmpty();
    }

    @Test
    void blankKeyIdAloneMeansInactive() {
        var registry = new ConnectionRegistry(
                props(Map.of("c1", cfg("stub", ConnectionConfig.Environment.PAPER, " ", "secret"))),
                List.of(factory("stub")));

        assertThat(registry.active()).isEmpty();
    }

    @Test
    void unknownProviderOnActiveConnectionFailsStartup() {
        var p = props(Map.of("c1", cfg("nope", ConnectionConfig.Environment.PAPER, "k", "s")));
        assertThatThrownBy(() -> new ConnectionRegistry(p, List.of(factory("stub"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("c1")
                .hasMessageContaining("nope");
    }

    @Test
    void unknownProviderOnInactiveConnectionIsIgnored() {
        var registry = new ConnectionRegistry(
                props(Map.of("c1", cfg("nope", ConnectionConfig.Environment.PAPER, "", ""))),
                List.of(factory("stub")));
        assertThat(registry.active()).isEmpty();
    }

    @Test
    void missingEnvironmentOnActiveConnectionFailsStartup() {
        var c = cfg("stub", null, "k", "s");
        var p = props(Map.of("c1", c));
        assertThatThrownBy(() -> new ConnectionRegistry(p, List.of(factory("stub"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("environment");
    }

    @Test
    void duplicateProviderKeyAcrossFactoriesFailsStartupWithClearMessage() {
        var p = props(Map.of("c1", cfg("stub", ConnectionConfig.Environment.PAPER, "k", "s")));
        assertThatThrownBy(() -> new ConnectionRegistry(p, List.of(factory("stub"), factory("stub"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stub")
                .hasMessageContaining("duplicate");
    }

    @Test
    void multipleConnectionsSameProviderGetDistinctInstances() {
        var connections = new LinkedHashMap<String, ConnectionConfig>();
        connections.put("a", cfg("stub", ConnectionConfig.Environment.PAPER, "k1", "s1"));
        connections.put("b", cfg("stub", ConnectionConfig.Environment.LIVE, "k2", "s2"));
        var registry = new ConnectionRegistry(props(connections), List.of(factory("stub")));

        assertThat(registry.active()).hasSize(2);
        assertThat(registry.get("a").orElseThrow().provider())
                .isNotSameAs(registry.get("b").orElseThrow().provider());
    }
}
