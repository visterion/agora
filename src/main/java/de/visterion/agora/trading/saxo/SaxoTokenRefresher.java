package de.visterion.agora.trading.saxo;

import de.visterion.agora.trading.ConnectionRegistry;
import de.visterion.agora.trading.ProbeStatus;
import de.visterion.agora.trading.RegisteredConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
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

    /**
     * Warm the Saxo sessions up before the startup probe runs. tick() already treats a
     * missing access token as a refresh trigger, so at boot it exchanges the persisted
     * refresh token for a fresh access token. @Order(0) runs this before ConnectionProbeRunner
     * (@Order(100)) so the probe sees a valid token instead of a warming-up connection.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    public void warmUp() {
        tick();
    }

    @Scheduled(fixedDelayString = "${agora.trading.saxo.refresh-check-ms:30000}")
    public synchronized void tick() {
        for (RegisteredConnection c : registry.active()) {
            if (!"saxo".equals(c.config().getProvider())) continue;
            SaxoTokenStore store = stores.forConnection(c.id());
            if (store.dead() || !store.hasRefreshToken()) continue;
            boolean noAccess = store.validAccessToken().isEmpty();
            boolean expiringSoon = !noAccess && store.accessRemainingMillis() < store.accessTtlMillis() / 3;
            if (!noAccess && !expiringSoon) continue;
            // H7: capture the refresh token in hand BEFORE the network call (which can take
            // up to the provider timeout) and only ever apply the result via the CAS
            // variants below — a concurrent /auth/saxo/callback (human re-authorizing) may
            // land fresh tokens while this refresh is in flight, and this stale result must
            // not clobber or kill that fresh session.
            String inHand = store.refreshToken();
            try {
                SaxoOAuthClient.SaxoTokens t = oauth.refresh(c.config(), inHand);
                boolean applied = store.updateIfCurrent(inHand, t.accessToken(), t.expiresInSeconds(), t.refreshToken());
                if (!applied) {
                    log.info("Saxo connection '{}': stale refresh result discarded (concurrent re-auth won)", c.id());
                    continue;
                }
                c.setProbeStatus(ProbeStatus.ok(Instant.now()));
                log.info("Saxo connection '{}' token refreshed", c.id());
            } catch (SaxoOAuthClient.InvalidGrantException e) {
                boolean applied = store.markDeadIfCurrent(inHand, "refresh rejected");
                if (!applied) {
                    log.info("Saxo connection '{}': stale refresh result discarded (concurrent re-auth won)", c.id());
                    continue;
                }
                c.setProbeStatus(ProbeStatus.unreachable(Instant.now(),
                        "refresh rejected — re-authorize via /auth/saxo/login"));
                log.warn("Saxo connection '{}' refresh rejected — re-authorize via /auth/saxo/login?connection={}",
                        c.id(), c.id());
            } catch (Exception e) {
                // Log the cause (SaxoOAuthClient categorises to "token endpoint HTTP 401",
                // "saxo app credentials rejected", "token endpoint unreachable: ..." etc.) so a
                // dead session is diagnosable — the Saxo token endpoint carries no secret in the
                // URL (Basic-auth header), so the message is safe to log.
                log.warn("Saxo connection '{}' token refresh failed (will retry): {}",
                        c.id(), e.toString());
            }
        }
    }
}
