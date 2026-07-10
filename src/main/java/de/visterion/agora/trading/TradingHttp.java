package de.visterion.agora.trading;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

/**
 * HTTP plumbing shared by broker providers: Apache client with automatic retries
 * disabled (order POSTs must never silently replay) and hard connect/response/socket
 * timeouts. Timeouts MUST live on the Apache client itself — with a custom HttpClient,
 * Spring's factory-level timeout setters are no-ops.
 */
public final class TradingHttp {

    private static final Timeout CONNECT_TIMEOUT = Timeout.ofSeconds(3);

    private TradingHttp() {}

    public static ClientHttpRequestFactory requestFactory(long responseTimeoutMs) {
        Timeout responseTimeout = Timeout.ofMilliseconds(responseTimeoutMs);
        // Apache's ConnectionConfig clashes with the trading-package ConnectionConfig — fully qualified.
        var connectionConfig = org.apache.hc.client5.http.config.ConnectionConfig.custom()
                .setConnectTimeout(CONNECT_TIMEOUT)
                .setSocketTimeout(responseTimeout)
                .build();
        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(connectionConfig)
                .build();
        return new HttpComponentsClientHttpRequestFactory(HttpClients.custom()
                .disableAutomaticRetries()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(responseTimeout)
                        .build())
                .build());
    }
}
