package de.visterion.agora.trading;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds a BrokerService with exactly one active PAPER connection for tool tests. */
public final class TestConnections {

    /** The connection id tool tests pass as the "connection" argument. */
    public static final String CONN = "test-conn";

    private TestConnections() {}

    public static BrokerService service(BrokerProvider provider) {
        ConnectionConfig cfg = new ConnectionConfig();
        cfg.setProvider("stub");
        cfg.setEnvironment(ConnectionConfig.Environment.PAPER);
        cfg.setKeyId("k");
        cfg.setSecret("s");

        ConnectionsProperties props = new ConnectionsProperties();
        props.setConnections(Map.of(CONN, cfg));

        BrokerProviderFactory factory = new BrokerProviderFactory() {
            public String provider() { return "stub"; }
            public BrokerProvider create(ConnectionConfig c) { return provider; }
        };

        ConnectionRegistry registry = new ConnectionRegistry(props, List.of(factory));
        LiveAccessGuard guard = new LiveAccessGuard(Set.of(), () -> null);
        return new BrokerService(registry, guard);
    }
}
