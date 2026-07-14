package de.visterion.agora.trading;

import de.visterion.agora.observability.ProviderCallLogger;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP plumbing shared by broker providers: Apache client with automatic retries
 * disabled (order POSTs must never silently replay) and hard connect/response/socket
 * timeouts. Timeouts MUST live on the Apache client itself — with a custom HttpClient,
 * Spring's factory-level timeout setters are no-ops.
 */
public final class TradingHttp {

    /** Shared default response timeout for broker/OAuth clients when no explicit value is configured. */
    public static final long DEFAULT_TIMEOUT_MS = 10_000L;

    private static final Timeout CONNECT_TIMEOUT = Timeout.ofSeconds(3);

    /**
     * Pool-checkout timeout (M-T9): how long a request waits for a free connection from the
     * pool before failing. Apache's default is 3 MINUTES — with a bounded pool, a 6th
     * concurrent call to the same broker would otherwise queue silently for up to 180s
     * instead of failing fast.
     */
    private static final Timeout CONNECTION_REQUEST_TIMEOUT = Timeout.ofSeconds(3);

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
                        .setConnectionRequestTimeout(CONNECTION_REQUEST_TIMEOUT)
                        .build())
                .build());
    }

    /** RestClient.Builder pre-wired with the retries-disabled Apache factory AND the provider-call logging interceptor. */
    public static RestClient.Builder clientBuilder(long responseTimeoutMs) {
        return RestClient.builder()
                .requestFactory(requestFactory(responseTimeoutMs))
                .requestInterceptor(ProviderCallLogger.INSTANCE);
    }
}
