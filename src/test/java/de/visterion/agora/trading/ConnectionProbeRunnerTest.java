package de.visterion.agora.trading;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionProbeRunnerTest {

    private static BrokerProvider probeStub(boolean failing) {
        return new BrokerProvider() {
            public String name() { return "stub"; }
            public OrderResult submitBracket(BracketOrderRequest r) { return null; }
            public OrderResult modifyBracket(String id, BigDecimal s, BigDecimal t) { return null; }
            public OrderResult flatten(String sym) { return null; }
            public List<Position> positions() { return List.of(); }
            public List<Order> orders(String status) { return List.of(); }
            public Account account() { return null; }
            public Order orderByClientRef(String ref) { return null; }
            public OrderResult cancel(String id) { return null; }
            public void probe() {
                if (failing) throw new BrokerException(BrokerException.Kind.UNAVAILABLE, "401 unauthorized", null);
            }
        };
    }

    private static ConnectionConfig cfg(String provider) {
        ConnectionConfig c = new ConnectionConfig();
        c.setProvider(provider);
        c.setEnvironment(ConnectionConfig.Environment.PAPER);
        c.setKeyId("k");
        c.setSecret("s");
        return c;
    }

    private static ConnectionRegistry registryWith(Map<String, Boolean> failingById) {
        Map<String, ConnectionConfig> conns = new LinkedHashMap<>();
        failingById.forEach((id, failing) -> conns.put(id, cfg(failing ? "bad" : "good")));
        ConnectionsProperties props = new ConnectionsProperties();
        props.setConnections(conns);
        BrokerProviderFactory good = new BrokerProviderFactory() {
            public String provider() { return "good"; }
            public BrokerProvider create(ConnectionConfig c) { return probeStub(false); }
        };
        BrokerProviderFactory bad = new BrokerProviderFactory() {
            public String provider() { return "bad"; }
            public BrokerProvider create(ConnectionConfig c) { return probeStub(true); }
        };
        return new ConnectionRegistry(props, List.of(good, bad));
    }

    @Test
    void okProbeSetsOkStatus() {
        var reg = registryWith(Map.of("c1", false));
        new ConnectionProbeRunner(reg).probeAll();
        var st = reg.get("c1").orElseThrow().probeStatus();
        assertThat(st.state()).isEqualTo("ok");
        assertThat(st.probedAt()).isNotNull();
        assertThat(st.detail()).isNull();
    }

    @Test
    void failingProbeSetsUnreachableAndDoesNotThrow() {
        var reg = registryWith(Map.of("c1", true));
        new ConnectionProbeRunner(reg).probeAll();   // must not throw
        var st = reg.get("c1").orElseThrow().probeStatus();
        assertThat(st.state()).isEqualTo("unreachable");
        assertThat(st.detail()).contains("401");
    }

    @Test
    void oneFailingConnectionDoesNotStopOthers() {
        var conns = new LinkedHashMap<String, Boolean>();
        conns.put("bad-1", true);
        conns.put("good-1", false);
        var reg = registryWith(conns);
        new ConnectionProbeRunner(reg).probeAll();
        assertThat(reg.get("bad-1").orElseThrow().probeStatus().state()).isEqualTo("unreachable");
        assertThat(reg.get("good-1").orElseThrow().probeStatus().state()).isEqualTo("ok");
    }
}
