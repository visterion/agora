package de.visterion.agora.trading.saxo;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** One-shot CSRF state values binding an OAuth callback to a previously initiated login. */
@Component
public class SaxoAuthState {

    private static final long TTL_MILLIS = 5 * 60 * 1000L;
    private record Pending(String connectionId, long expiresAt) {}

    private final SecureRandom random = new SecureRandom();
    private final LongSupplier nowMillis;
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();

    public SaxoAuthState() { this(System::currentTimeMillis); }
    SaxoAuthState(LongSupplier nowMillis) { this.nowMillis = nowMillis; }

    public String issue(String connectionId) {
        // Lazy sweep: remove all expired entries before issuing new state
        long now = nowMillis.getAsLong();
        pending.entrySet().removeIf(e -> now > e.getValue().expiresAt());

        // Hard cap: reject the new issue rather than evict a still-live pending login —
        // an unauthenticated login flood must never be able to deny a legitimate operator's
        // in-flight authorization.
        if (pending.size() >= 1000) {
            throw new IllegalStateException("too many pending authorizations");
        }

        byte[] b = new byte[16];
        random.nextBytes(b);
        String state = HexFormat.of().formatHex(b);
        pending.put(state, new Pending(connectionId, now + TTL_MILLIS));
        return state;
    }

    public Optional<String> consume(String state) {
        Pending p = pending.remove(state);
        if (p == null || nowMillis.getAsLong() > p.expiresAt()) return Optional.empty();
        return Optional.of(p.connectionId());
    }
}
