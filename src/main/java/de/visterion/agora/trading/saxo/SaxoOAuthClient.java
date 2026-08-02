package de.visterion.agora.trading.saxo;

import de.visterion.agora.observability.ProviderCallLogger;
import de.visterion.agora.trading.ConnectionConfig;
import de.visterion.agora.trading.TradingHttp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.time.Duration;

/** Code-exchange and refresh against Saxo's logonvalidation token endpoint. */
@Component
public class SaxoOAuthClient {

    /**
     * Token response. {@code refreshExpiresInSeconds} is Saxo's own
     * {@code refresh_token_expires_in} (3600s in production, sent on both the
     * authorization-code and the refresh grant); {@code 0} means the response carried no
     * figure — callers must then keep whatever deadline they already have rather than
     * inventing one.
     */
    public record SaxoTokens(String accessToken, long expiresInSeconds, String refreshToken,
            long refreshExpiresInSeconds) {

        public SaxoTokens(String accessToken, long expiresInSeconds, String refreshToken) {
            this(accessToken, expiresInSeconds, refreshToken, 0L);
        }
    }

    /**
     * Refresh/auth code definitively rejected (HTTP 400) — re-auth required. HTTP 401 is
     * deliberately NOT mapped here: Saxo uses it for a dead grant as well as for bad app
     * credentials, so it cannot carry that meaning on its own — see {@link #post}.
     */
    public static class InvalidGrantException extends RuntimeException {
        public InvalidGrantException(String message) { super(message); }
    }

    // JdkClientHttpRequestFactory's read timeout bounds the entire exchange (connect + response)
    // for a plain, non-pooled client — unlike TradingHttp's Apache client, there is no separate
    // connect-timeout leg to configure, so no 3s/10s split is needed here.
    private final JdkClientHttpRequestFactory requestFactory;

    @Autowired
    public SaxoOAuthClient(@Value("${agora.trading.provider-timeout-ms:10000}") long timeoutMs) {
        this.requestFactory = new JdkClientHttpRequestFactory();
        this.requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));
    }

    /** Default-timeout convenience ctor (tests). */
    public SaxoOAuthClient() { this(TradingHttp.DEFAULT_TIMEOUT_MS); }

    public SaxoTokens exchangeCode(ConnectionConfig cfg, String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        String redirect = cfg.getExtra().get("redirect-uri");
        if (redirect != null && !redirect.isBlank()) form.add("redirect_uri", redirect);
        return post(cfg, form);
    }

    public SaxoTokens refresh(ConnectionConfig cfg, String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        return post(cfg, form);
    }

    private SaxoTokens post(ConnectionConfig cfg, MultiValueMap<String, String> form) {
        try {
            JsonNode n = RestClient.builder().baseUrl(authBaseUrl(cfg))
                    .requestFactory(requestFactory)
                    .requestInterceptor(ProviderCallLogger.INSTANCE)
                    .build()
                    .post().uri("/token")
                    .headers(h -> h.setBasicAuth(cfg.getKeyId(), cfg.getSecret()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
            if (n == null || n.path("access_token").isMissingNode()) {
                throw new IllegalStateException("empty token response");
            }
            return new SaxoTokens(
                    n.path("access_token").asString(null),
                    n.path("expires_in").asLong(1200),
                    n.path("refresh_token").asString(null),
                    n.path("refresh_token_expires_in").asLong(0L));
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 400) {
                throw new InvalidGrantException("token grant rejected (HTTP " + status + ")");
            }
            if (status == 401) {
                // Saxo answers 401 for BOTH a rejected app key/secret (Basic Auth on this
                // request) and a dead refresh token — verified in production on 2026-08-01,
                // where the body was Saxo's generic HTML error page, not a JSON invalid_grant.
                // So 401 alone cannot tell session death from misconfiguration: it stays
                // retryable, and SaxoTokenRefresher decides death by the refresh-token
                // deadline instead.
                throw new IllegalStateException(
                        "saxo token endpoint rejected the request (HTTP 401) — dead grant or "
                                + "wrong app credentials");
            }
            throw new IllegalStateException("token endpoint HTTP " + status, e);
        } catch (ResourceAccessException e) {
            throw new IllegalStateException("token endpoint unreachable: " + e.getMessage(), e);
        }
    }

    public static String authBaseUrl(ConnectionConfig cfg) {
        String override = cfg.getExtra() == null ? null : cfg.getExtra().get("auth-base-url");
        if (override != null && !override.isBlank()) return override;
        return cfg.getEnvironment() == ConnectionConfig.Environment.LIVE
                ? "https://live.logonvalidation.net"
                : "https://sim.logonvalidation.net";
    }
}
