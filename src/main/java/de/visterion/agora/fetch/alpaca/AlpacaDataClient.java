package de.visterion.agora.fetch.alpaca;

import de.visterion.agora.data.DataHttp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Shared Alpaca market-data REST access (data.alpaca.markets), header-authed.
 *  Reusable base for Alpaca-backed agora-data services (splits now; quotes/bars later).
 *  Carries the configurable per-request read timeout so a slow Alpaca call fails fast. */
@Component
public class AlpacaDataClient {

    private final RestClient http;
    private final boolean configured;

    @Autowired
    public AlpacaDataClient(
            @Value("${agora.data.alpaca.base-url:https://data.alpaca.markets}") String baseUrl,
            @Value("${agora.data.alpaca.key-id:}") String keyId,
            @Value("${agora.data.alpaca.secret:}") String secret,
            @Value("${agora.data.provider-timeout-ms:4000}") long timeoutMs) {
        this.configured = keyId != null && !keyId.isBlank() && secret != null && !secret.isBlank();
        JdkClientHttpRequestFactory rf = DataHttp.requestFactory(timeoutMs);
        this.http = RestClient.builder()
                .requestFactory(rf)
                .baseUrl(baseUrl)
                .defaultHeader("APCA-API-KEY-ID", keyId)
                .defaultHeader("APCA-API-SECRET-KEY", secret)
                .build();
    }

    /** Convenience constructor (default per-request timeout); used by tests. */
    public AlpacaDataClient(String baseUrl, String keyId, String secret) {
        this(baseUrl, keyId, secret, 4000L);
    }

    // public: cross-package tests (fetch.split) construct this directly
    public AlpacaDataClient(RestClient http, boolean configured) { this.http = http; this.configured = configured; }

    public boolean configured() { return configured; }
    public RestClient http() { return http; }
}
