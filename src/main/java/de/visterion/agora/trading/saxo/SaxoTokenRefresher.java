package de.visterion.agora.trading.saxo;

import de.visterion.agora.trading.ConnectionRegistry;
import de.visterion.agora.trading.ProbeStatus;
import de.visterion.agora.trading.RegisteredConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Keeps Saxo sessions alive: Saxo access tokens live ~20min and refresh tokens roll on
 * every use, so each active saxo connection is refreshed before expiry. A definitively
 * rejected refresh (invalid_grant) marks the store dead — healing requires a human
 * re-auth via /auth/saxo/login. Transient failures retry on the next tick.
 */
@Component
public class SaxoTokenRefresher {

    private static final Logger log = LoggerFactory.getLogger(SaxoTokenRefresher.class);

    private final ConnectionRegistry registry;
    private final SaxoTokenStores stores;
    private final SaxoOAuthClient oauth;

    public SaxoTokenRefresher(ConnectionRegistry registry, SaxoTokenStores stores, SaxoOAuthClient oauth) {
        this.registry = registry;
        this.stores = stores;
        this.oauth = oauth;
    }

    @Scheduled(fixedDelayString = "${agora.trading.saxo.refresh-check-ms:30000}")
    public void tick() {
        for (RegisteredConnection c : registry.active()) {
            if (!"saxo".equals(c.config().getProvider())) continue;
            SaxoTokenStore store = stores.forConnection(c.id());
            if (store.dead() || !store.hasRefreshToken()) continue;
            boolean noAccess = store.validAccessToken().isEmpty();
            boolean expiringSoon = !noAccess && store.accessRemainingMillis() < store.accessTtlMillis() / 3;
            if (!noAccess && !expiringSoon) continue;
            try {
                SaxoOAuthClient.SaxoTokens t = oauth.refresh(c.config(), store.refreshToken());
                store.update(t.accessToken(), t.expiresInSeconds(), t.refreshToken());
                c.setProbeStatus(ProbeStatus.ok(Instant.now()));
                log.info("Saxo connection '{}' token refreshed", c.id());
            } catch (SaxoOAuthClient.InvalidGrantException e) {
                store.markDead("refresh rejected");
                c.setProbeStatus(ProbeStatus.unreachable(Instant.now(),
                        "refresh rejected — re-authorize via /auth/saxo/login"));
                log.warn("Saxo connection '{}' refresh rejected — re-authorize via /auth/saxo/login?connection={}",
                        c.id(), c.id());
            } catch (Exception e) {
                log.warn("Saxo connection '{}' token refresh failed (will retry): {}",
                        c.id(), e.getClass().getSimpleName());
            }
        }
    }
}
