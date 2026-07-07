package de.visterion.agora.trading;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Binds agora.trading.connections.* — other agora.trading keys are read via @Value elsewhere. */
@Component
@ConfigurationProperties(prefix = "agora.trading")
public class ConnectionsProperties {
    private Map<String, ConnectionConfig> connections = new LinkedHashMap<>();
    public Map<String, ConnectionConfig> getConnections() { return connections; }
    public void setConnections(Map<String, ConnectionConfig> connections) { this.connections = connections; }
}
