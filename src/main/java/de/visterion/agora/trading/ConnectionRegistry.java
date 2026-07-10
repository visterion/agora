package de.visterion.agora.trading;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds one BrokerProvider instance per ACTIVE connection (credentials set) at startup.
 * Unknown provider or missing environment on an ACTIVE connection is a config error → startup fails.
 * Inactive connections are silently skipped.
 */
@Component
public class ConnectionRegistry {

    private final Map<String, RegisteredConnection> connections;

    public ConnectionRegistry(ConnectionsProperties props, List<BrokerProviderFactory> factories) {
        Map<String, BrokerProviderFactory> byProvider = factories.stream()
                .collect(Collectors.toMap(BrokerProviderFactory::provider, Function.identity()));

        Map<String, RegisteredConnection> out = new LinkedHashMap<>();
        props.getConnections().forEach((id, cfg) -> {
            if (!cfg.active()) return;
            if (cfg.getEnvironment() == null) {
                throw new IllegalStateException("connection '" + id + "': environment is required (paper|live)");
            }
            BrokerProviderFactory factory = byProvider.get(cfg.getProvider());
            if (factory == null) {
                throw new IllegalStateException(
                        "connection '" + id + "': unknown provider '" + cfg.getProvider() + "'");
            }
            out.put(id, new RegisteredConnection(id, cfg, factory.create(id, cfg)));
        });
        this.connections = Collections.unmodifiableMap(out);
    }

    public Optional<RegisteredConnection> get(String id) {
        return Optional.ofNullable(connections.get(id));
    }

    public List<RegisteredConnection> active() {
        return List.copyOf(connections.values());
    }
}
