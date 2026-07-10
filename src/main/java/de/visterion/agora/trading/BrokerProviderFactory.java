package de.visterion.agora.trading;

/** Builds one BrokerProvider instance per active connection. One factory per broker integration. */
public interface BrokerProviderFactory {
    /** Provider key matched against ConnectionConfig.provider, e.g. "alpaca". */
    String provider();
    /**
     * @param connectionId the yaml connection id (map key in {@code agora.trading.connections}) —
     *                      factories that key per-connection state (e.g. Saxo's token store) MUST
     *                      use this, not a value derived from {@code cfg} (M-T7).
     */
    BrokerProvider create(String connectionId, ConnectionConfig cfg);
}
