package de.visterion.agora.trading;

/** An active connection: immutable identity + provider instance, mutable probe status. */
public final class RegisteredConnection {
    private final String id;
    private final ConnectionConfig config;
    private final BrokerProvider provider;
    private volatile ProbeStatus probeStatus = ProbeStatus.unknown();

    public RegisteredConnection(String id, ConnectionConfig config, BrokerProvider provider) {
        this.id = id;
        this.config = config;
        this.provider = provider;
    }

    public String id() { return id; }
    public ConnectionConfig config() { return config; }
    public BrokerProvider provider() { return provider; }
    public ProbeStatus probeStatus() { return probeStatus; }
    public void setProbeStatus(ProbeStatus status) { this.probeStatus = status; }
}
