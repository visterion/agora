package de.visterion.agora.trading;

/** Builds one BrokerProvider instance per active connection. One factory per broker integration. */
public interface BrokerProviderFactory {
    /** Provider key matched against ConnectionConfig.provider, e.g. "alpaca". */
    String provider();
    BrokerProvider create(ConnectionConfig cfg);
}
