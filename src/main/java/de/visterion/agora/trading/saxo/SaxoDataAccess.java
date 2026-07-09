package de.visterion.agora.trading.saxo;

import de.visterion.agora.trading.ConnectionRegistry;
import de.visterion.agora.trading.RegisteredConnection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Narrow bridge from the trading layer to the data layer: exposes the HTTP client, bearer
 * and AccountKey of ONE configured saxo connection (default saxo-live) so market-data
 * providers can read Saxo endpoints without depending on trading internals. When the
 * connection is missing, not saxo, or has no live session, the bridge yields empty values —
 * the data provider then self-skips with UNAVAILABLE ("falls vorhanden" contract).
 */
@Component
public class SaxoDataAccess {

    private final RestClient http;
    private final Supplier<Optional<String>> bearer;
    private volatile String cachedAccountKey;

    @Autowired
    public SaxoDataAccess(ConnectionRegistry registry, SaxoTokenStores stores,
                          @Value("${agora.data.saxo.connection:saxo-live}") String connectionId,
                          @Value("${agora.data.provider-timeout-ms:4000}") long timeoutMs) {
        Optional<RegisteredConnection> rc = registry.get(connectionId)
                .filter(c -> "saxo".equals(c.config().getProvider()));
        if (rc.isEmpty() || rc.get().config().getBaseUrl() == null) {
            this.http = null;
            this.bearer = Optional::empty;
            return;
        }
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory();
        rf.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.http = RestClient.builder()
                .baseUrl(rc.get().config().getBaseUrl())
                .requestFactory(rf)
                .build();
        SaxoTokenStore store = stores.forConnection(connectionId);
        this.bearer = () -> store.validAccessToken().map(t -> "Bearer " + t);
    }

    /**
     * Test constructor: explicit client (null = disabled bridge) + bearer supplier.
     * Public (not package-private) because data-package tests construct the bridge too.
     */
    public SaxoDataAccess(RestClient http, Supplier<Optional<String>> bearer) {
        this.http = http;
        this.bearer = bearer;
    }

    /** "Bearer <token>" for the live session, or empty when the bridge is unusable. */
    public Optional<String> bearer() {
        return http == null ? Optional.empty() : bearer.get();
    }

    /** Prebuilt client for the Saxo gateway; null when the bridge is disabled. */
    public RestClient http() {
        return http;
    }

    /**
     * AccountKey required by the chart endpoint. Fetched lazily from /port/v1/accounts/me,
     * cached forever after the first success (stable per account). Failures are not cached.
     */
    public Optional<String> accountKey() {
        String ck = cachedAccountKey;
        if (ck != null) return Optional.of(ck);
        Optional<String> b = bearer();
        if (b.isEmpty()) return Optional.empty();
        try {
            JsonNode n = http.get().uri("/port/v1/accounts/me")
                    .header("Authorization", b.get())
                    .retrieve().body(JsonNode.class);
            String key = n == null ? "" : n.path("Data").path(0).path("AccountKey").asString("");
            if (key.isBlank()) return Optional.empty();
            cachedAccountKey = key;
            return Optional.of(key);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
