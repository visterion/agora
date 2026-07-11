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
 * INVARIANT (M-T7): the token-store key equals the yaml connection id — whatever key the
 * connection is registered under in {@code agora.trading.connections} (conventionally
 * depot-1 / saxo-live, but not required to be). This MUST match the key used by the
 * refresher, the data provider, and the auth endpoints (/auth/saxo/login?connection=<id>),
 * all of which key by connection id — not by a value derived from the environment.
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

    @Override
    public BrokerProvider create(String connectionId, ConnectionConfig cfg) {
        RestClient client = RestClient.builder()
                .requestFactory(TradingHttp.requestFactory(timeoutMs))
                .baseUrl(cfg.getBaseUrl())
                .build();
        SaxoTokenStore store = stores.forConnection(connectionId);
        SaxoInstrumentResolver resolver = new SaxoInstrumentResolver(client,
                store::authorizationHeaderValue,
                cfg.getExtra() == null ? null : cfg.getExtra().get("exchange-id"),
                86_400_000L, System::currentTimeMillis);
        return new SaxoBrokerProvider(cfg, store, client, resolver);
    }
}
