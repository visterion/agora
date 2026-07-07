package de.visterion.agora.trading;

import org.springframework.stereotype.Component;

/** Builds one AlpacaBrokerProvider per active alpaca connection. */
@Component
public class AlpacaBrokerProviderFactory implements BrokerProviderFactory {

    @Override
    public String provider() { return "alpaca"; }

    @Override
    public BrokerProvider create(ConnectionConfig cfg) {
        return new AlpacaBrokerProvider(cfg.getBaseUrl(), cfg.getKeyId(), cfg.getSecret());
    }
}
