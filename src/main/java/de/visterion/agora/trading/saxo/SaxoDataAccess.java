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
import java.util.function.LongSupplier;
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

    /** AccountKey cache TTL — long enough that steady-state cost is one call ever, short
     * enough that a genuine account change (rare, but not impossible: re-auth against a
     * different Saxo login) heals itself without a restart. */
    private static final long ACCOUNT_KEY_TTL_MILLIS = 3_600_000L; // 1h

    private final RestClient http;
    private final Supplier<Optional<String>> bearer;
    /** Matches the connection's {@code extra.account-key}, when configured; else Data[0] wins. */
    private final String configuredAccountKey;
    private final LongSupplier nowMillis;
    private volatile String cachedAccountKey;
    private volatile long cachedAccountKeyExpiresAt;

    @Autowired
    public SaxoDataAccess(ConnectionRegistry registry, SaxoTokenStores stores,
                          @Value("${agora.data.saxo.connection:saxo-live}") String connectionId,
                          @Value("${agora.data.provider-timeout-ms:4000}") long timeoutMs) {
        Optional<RegisteredConnection> rc = registry.get(connectionId)
                .filter(c -> "saxo".equals(c.config().getProvider()));
        if (rc.isEmpty() || rc.get().config().getBaseUrl() == null) {
            this.http = null;
            this.bearer = Optional::empty;
            this.configuredAccountKey = null;
            this.nowMillis = System::currentTimeMillis;
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
        this.configuredAccountKey = rc.get().config().getExtra() == null
                ? null : rc.get().config().getExtra().get("account-key");
        this.nowMillis = System::currentTimeMillis;
    }

    /**
     * Test constructor: explicit client (null = disabled bridge) + bearer supplier.
     * Public (not package-private) because data-package tests construct the bridge too.
     */
    public SaxoDataAccess(RestClient http, Supplier<Optional<String>> bearer) {
        this(http, bearer, null, System::currentTimeMillis);
    }

    /** Test constructor exposing account-key selection + a deterministic clock for the TTL. */
    public SaxoDataAccess(RestClient http, Supplier<Optional<String>> bearer,
                          String configuredAccountKey, LongSupplier nowMillis) {
        this.http = http;
        this.bearer = bearer;
        this.configuredAccountKey = configuredAccountKey;
        this.nowMillis = nowMillis;
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
     * cached for {@link #ACCOUNT_KEY_TTL_MILLIS} after each success (not forever — a stale
     * cached key from before a re-auth against a different account would otherwise never
     * heal). When the connection has {@code extra.account-key} set, this picks the matching
     * account from the response; otherwise it takes Data[0], as before. Failures are not
     * cached.
     */
    public Optional<String> accountKey() {
        String ck = cachedAccountKey;
        if (ck != null && nowMillis.getAsLong() < cachedAccountKeyExpiresAt) {
            return Optional.of(ck);
        }
        Optional<String> b = bearer();
        if (b.isEmpty()) return Optional.empty();
        try {
            JsonNode n = http.get().uri("/port/v1/accounts/me")
                    .header("Authorization", b.get())
                    .retrieve().body(JsonNode.class);
            JsonNode data = n == null ? null : n.path("Data");
            if (data == null || !data.isArray() || data.isEmpty()) return Optional.empty();
            JsonNode chosen = null;
            if (configuredAccountKey != null && !configuredAccountKey.isBlank()) {
                for (JsonNode acc : data) {
                    if (configuredAccountKey.equals(acc.path("AccountKey").asString(null))) {
                        chosen = acc;
                        break;
                    }
                }
            }
            if (chosen == null) chosen = data.path(0);
            String key = chosen.path("AccountKey").asString("");
            if (key.isBlank()) return Optional.empty();
            cachedAccountKey = key;
            cachedAccountKeyExpiresAt = nowMillis.getAsLong() + ACCOUNT_KEY_TTL_MILLIS;
            return Optional.of(key);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
