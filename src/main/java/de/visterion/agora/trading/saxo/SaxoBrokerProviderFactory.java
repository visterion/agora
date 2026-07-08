package de.visterion.agora.trading.saxo;

import de.visterion.agora.trading.BrokerProvider;
import de.visterion.agora.trading.BrokerProviderFactory;
import de.visterion.agora.trading.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Builds one SaxoBrokerProvider per active saxo connection.
 * INVARIANT: the token-store key equals the yaml connection id — saxo-sim (PAPER) /
 * saxo-live (LIVE). v1 supports exactly one saxo connection per environment; the auth
 * endpoints (/auth/saxo/login?connection=saxo-sim) write to the same keys.
 */
@Component
public class SaxoBrokerProviderFactory implements BrokerProviderFactory {

    private final SaxoTokenStores stores;

    public SaxoBrokerProviderFactory(SaxoTokenStores stores) { this.stores = stores; }

    @Override
    public String provider() { return "saxo"; }

    static String storeKey(ConnectionConfig cfg) {
        return cfg.getEnvironment() == ConnectionConfig.Environment.LIVE ? "saxo-live" : "saxo-sim";
    }

    @Override
    public BrokerProvider create(ConnectionConfig cfg) {
        RestClient client = RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(
                        HttpClients.custom().disableAutomaticRetries().build()))
                .baseUrl(cfg.getBaseUrl())
                .build();
        return new SaxoBrokerProvider(cfg, stores.forConnection(storeKey(cfg)), client);
    }
}
