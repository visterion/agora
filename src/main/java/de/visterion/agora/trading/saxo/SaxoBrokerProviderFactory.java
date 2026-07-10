package de.visterion.agora.trading.saxo;

import de.visterion.agora.trading.BrokerProvider;
import de.visterion.agora.trading.BrokerProviderFactory;
import de.visterion.agora.trading.ConnectionConfig;
import de.visterion.agora.trading.TradingHttp;
import org.springframework.beans.factory.annotation.Value;
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
    private final long timeoutMs;

    public SaxoBrokerProviderFactory(SaxoTokenStores stores,
            @Value("${agora.trading.provider-timeout-ms:10000}") long timeoutMs) {
        this.stores = stores;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public String provider() { return "saxo"; }

    static String storeKey(ConnectionConfig cfg) {
        return cfg.getEnvironment() == ConnectionConfig.Environment.LIVE ? "saxo-live" : "saxo-sim";
    }

    @Override
    public BrokerProvider create(ConnectionConfig cfg) {
        RestClient client = RestClient.builder()
                .requestFactory(TradingHttp.requestFactory(timeoutMs))
                .baseUrl(cfg.getBaseUrl())
                .build();
        SaxoTokenStore store = stores.forConnection(storeKey(cfg));
        SaxoInstrumentResolver resolver = new SaxoInstrumentResolver(client,
                store::authorizationHeaderValue,
                cfg.getExtra() == null ? null : cfg.getExtra().get("exchange-id"),
                86_400_000L, System::currentTimeMillis);
        return new SaxoBrokerProvider(cfg, store, client, resolver);
    }
}
