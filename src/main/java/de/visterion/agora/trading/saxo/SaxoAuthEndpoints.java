package de.visterion.agora.trading.saxo;

import de.visterion.agora.trading.ConnectionConfig;
import de.visterion.agora.trading.ConnectionRegistry;
import de.visterion.agora.trading.RegisteredConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Optional;

/**
 * OAuth code-flow front-door for saxo connections. The ONLY unauthenticated surface
 * besides /actuator/health; safety = one-shot state binding + Saxo one-time codes.
 * Responses never contain tokens, credentials, or exception details.
 */
@RestController
public class SaxoAuthEndpoints {

    private static final Logger log = LoggerFactory.getLogger(SaxoAuthEndpoints.class);

    private final ConnectionRegistry registry;
    private final SaxoTokenStores stores;
    private final SaxoAuthState states;
    private final SaxoOAuthClient oauth;

    public SaxoAuthEndpoints(ConnectionRegistry registry, SaxoTokenStores stores,
                             SaxoAuthState states, SaxoOAuthClient oauth) {
        this.registry = registry;
        this.stores = stores;
        this.states = states;
        this.oauth = oauth;
    }

    @GetMapping("/auth/saxo/login")
    public ResponseEntity<String> login(@RequestParam("connection") String connection) {
        Optional<RegisteredConnection> rc = registry.get(connection)
                .filter(c -> "saxo".equals(c.config().getProvider()));
        if (rc.isEmpty()) return ResponseEntity.status(404).body("unknown saxo connection");

        ConnectionConfig cfg = rc.get().config();
        String state = states.issue(connection);
        String url = UriComponentsBuilder
                .fromUriString(SaxoOAuthClient.authBaseUrl(cfg) + "/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", cfg.getKeyId())
                .queryParam("state", state)
                .queryParam("redirect_uri", cfg.getExtra().getOrDefault("redirect-uri", ""))
                .build().toUriString();
        return ResponseEntity.status(302).header("Location", url).body("");
    }

    @GetMapping("/auth/saxo/callback")
    public ResponseEntity<String> callback(@RequestParam("code") String code,
                                           @RequestParam("state") String state) {
        Optional<String> connection = states.consume(state);
        if (connection.isEmpty()) return ResponseEntity.status(400).body("invalid state");
        String id = connection.get();

        ConnectionConfig cfg = registry.get(id).map(RegisteredConnection::config).orElse(null);
        if (cfg == null) return ResponseEntity.status(400).body("invalid state");

        try {
            SaxoOAuthClient.SaxoTokens t = oauth.exchangeCode(cfg, code);
            stores.forConnection(id).update(t.accessToken(), t.expiresInSeconds(), t.refreshToken());
            log.info("Saxo connection '{}' authorized via OAuth callback", id);
            return ResponseEntity.ok("connection " + id + " authorized");
        } catch (Exception e) {
            log.warn("Saxo token exchange failed for connection '{}': {}", id, e.getClass().getSimpleName());
            return ResponseEntity.status(502).body("token exchange failed");
        }
    }
}
