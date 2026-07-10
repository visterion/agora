package de.visterion.agora.trading;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Builds one AlpacaBrokerProvider per active alpaca connection. */
@Component
public class AlpacaBrokerProviderFactory implements BrokerProviderFactory {

    private final long timeoutMs;

    public AlpacaBrokerProviderFactory(
            @Value("${agora.trading.provider-timeout-ms:10000}") long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    @Override
    public String provider() { return "alpaca"; }

    @Override
    public BrokerProvider create(String connectionId, ConnectionConfig cfg) {
        return new AlpacaBrokerProvider(cfg.getBaseUrl(), cfg.getKeyId(), cfg.getSecret(), timeoutMs);
    }
}
