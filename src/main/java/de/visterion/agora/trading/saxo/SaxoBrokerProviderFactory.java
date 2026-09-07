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
    private final long orderWriteMinIntervalMs;
    private final long orderWriteMaxBlockMs;
    private final long orderWriteDefaultRetryAfterMs;

    public SaxoBrokerProviderFactory(SaxoTokenStores stores,
            @Value("${agora.trading.provider-timeout-ms:10000}") long timeoutMs,
            @Value("${agora.trading.saxo.order-write-min-interval-ms:1100}") long orderWriteMinIntervalMs,
            @Value("${agora.trading.saxo.order-write-max-block-ms:3000}") long orderWriteMaxBlockMs,
            @Value("${agora.trading.saxo.order-write-default-retry-after-ms:1000}") long orderWriteDefaultRetryAfterMs) {
        this.stores = stores;
        this.timeoutMs = timeoutMs;
        this.orderWriteMinIntervalMs = orderWriteMinIntervalMs;
        this.orderWriteMaxBlockMs = orderWriteMaxBlockMs;
        this.orderWriteDefaultRetryAfterMs = orderWriteDefaultRetryAfterMs;
    }

    @Override
    public String provider() { return "saxo"; }

    @Override
    public BrokerProvider create(String connectionId, ConnectionConfig cfg) {
        // ONE pacer per connection: Saxo's order limit is per session, and exactly one
        // SaxoBrokerProvider/RestClient exists per active connection. Two connections do not
        // share a bucket. Registered ahead of ProviderCallLogger so its wait is not billed to
        // the logged dur_ms.
        SaxoOrderWritePacer pacer = new SaxoOrderWritePacer(
                orderWriteMinIntervalMs, orderWriteMaxBlockMs, orderWriteDefaultRetryAfterMs);
        RestClient client = TradingHttp.clientBuilder(timeoutMs, pacer)
                .baseUrl(cfg.getBaseUrl())
                .build();
        SaxoTokenStore store = stores.forConnection(connectionId);
        String preferredCurrency = cfg.getExtra() == null
                ? "USD"
                : cfg.getExtra().getOrDefault("preferred-currency", "USD");
        SaxoInstrumentResolver resolver = new SaxoInstrumentResolver(client,
                store::authorizationHeaderValue,
                cfg.getExtra() == null ? null : cfg.getExtra().get("exchange-id"),
                preferredCurrency,
                86_400_000L, System::currentTimeMillis);
        return new SaxoBrokerProvider(cfg, store, client, resolver);
    }
}
