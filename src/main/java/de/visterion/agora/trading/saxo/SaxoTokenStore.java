package de.visterion.agora.trading.saxo;

import de.visterion.agora.trading.BrokerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Token state for ONE Saxo connection. Only the (rolling) refresh token is persisted —
 * the short-lived access token lives in memory. The new refresh token is written to disk
 * BEFORE it replaces the in-memory one: a crash between refresh and persist must not
 * lose the session. File perms are owner-only; token values are never logged.
 *
 * <p>All mutable state is held as a single immutable {@link TokenState} snapshot behind
 * one volatile reference so readers never observe a torn/partial update: every reader
 * takes one volatile read of {@link #state}, and every mutator is {@code synchronized}
 * and swaps in a brand-new snapshot.
 */
public final class SaxoTokenStore {

    private static final Logger log = LoggerFactory.getLogger(SaxoTokenStore.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<PosixFilePermission> OWNER_RW =
            PosixFilePermissions.fromString("rw-------");
    private static final FileAttribute<Set<PosixFilePermission>> OWNER_RW_ATTR =
            PosixFilePermissions.asFileAttribute(OWNER_RW);
    private static final Set<PosixFilePermission> OWNER_RWX =
            PosixFilePermissions.fromString("rwx------");
    private static final FileAttribute<Set<PosixFilePermission>> OWNER_RWX_ATTR =
            PosixFilePermissions.asFileAttribute(OWNER_RWX);

    private static final TokenState UNAUTHENTICATED = new TokenState(null, 0L, 0L, null, null);

    private record TokenState(
            String accessToken,
            long accessExpiresAtMillis,
            long accessTtlMillis,
            String refreshToken,
            String deadReason) {
    }

    private final String connectionId;
    private final Path file;
    private final LongSupplier nowMillis;

    private volatile TokenState state = UNAUTHENTICATED;

    public SaxoTokenStore(String connectionId, Path dir, LongSupplier nowMillis) {
        this.connectionId = connectionId;
        this.file = dir.resolve(connectionId + ".token");
        this.nowMillis = nowMillis;
        createDirectoryOwnerOnly(dir);
        loadFile();
    }

    public synchronized void update(String accessToken, long expiresInSeconds, String newRefreshToken) {
        persistRefreshToken(newRefreshToken);            // disk first — crash-safe rolling
        long ttl = expiresInSeconds * 1000L;
        this.state = new TokenState(
                accessToken,
                nowMillis.getAsLong() + ttl,
                ttl,
                newRefreshToken,
                null);
    }

    public Optional<String> validAccessToken() {
        TokenState s = state;
        if (s.accessToken() == null || nowMillis.getAsLong() >= s.accessExpiresAtMillis()) {
            return Optional.empty();
        }
        return Optional.of(s.accessToken());
    }

    /**
     * The Authorization header value ("Bearer <access>") for a valid session, or a
     * state-aware BrokerException: NOT_READY when the connection is authorized but the
     * access token is still being refreshed; UNAVAILABLE otherwise (never authorized or dead).
     */
    public String authorizationHeaderValue() {
        if (dead()) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "saxo connection needs re-authorization — visit /auth/saxo/login?connection="
                            + connectionId, null);
        }
        Optional<String> access = validAccessToken();
        if (access.isPresent()) {
            return "Bearer " + access.get();
        }
        if (!hasRefreshToken()) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "saxo connection not authorized — visit /auth/saxo/login?connection="
                            + connectionId, null);
        }
        throw new BrokerException(BrokerException.Kind.NOT_READY,
                "saxo connection authorized, token refresh pending — retry shortly", null);
    }

    public boolean hasRefreshToken() { return state.refreshToken() != null; }
    public String refreshToken() { return state.refreshToken(); }

    public long accessTtlMillis() {
        TokenState s = state;
        return s.accessToken() == null ? 0 : s.accessTtlMillis();
    }

    public long accessRemainingMillis() {
        TokenState s = state;
        if (s.accessToken() == null) return 0;
        return Math.max(0, s.accessExpiresAtMillis() - nowMillis.getAsLong());
    }

    public synchronized void markDead(String reason) {
        TokenState s = this.state;
        this.state = new TokenState(
                s.accessToken(), s.accessExpiresAtMillis(), s.accessTtlMillis(), s.refreshToken(), reason);
    }

    public boolean dead() { return state.deadReason() != null; }
    public String deadReason() { return state.deadReason(); }
    public String connectionId() { return connectionId; }

    private void persistRefreshToken(String token) {
        try {
            createDirectoryOwnerOnly(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.deleteIfExists(tmp);
            Files.createFile(tmp, OWNER_RW_ATTR);
            ObjectNode json = MAPPER.createObjectNode();
            json.put("refreshToken", token);
            json.put("obtainedAtMillis", nowMillis.getAsLong());
            Files.writeString(tmp, json.toString(), StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist token file for " + connectionId, e);
        }
    }

    private void loadFile() {
        if (!Files.exists(file)) return;
        try {
            JsonNode n = MAPPER.readTree(Files.readString(file));
            JsonNode rt = n.path("refreshToken");
            if (!rt.isMissingNode() && !rt.isNull()) {
                String refreshToken = rt.asString(null);
                this.state = new TokenState(null, 0L, 0L, refreshToken, null);
            }
        } catch (Exception e) {
            log.warn("Saxo token file for '{}' unreadable — treating as unauthorized", connectionId);
        }
    }

    private static void createDirectoryOwnerOnly(Path dir) {
        try {
            Files.createDirectories(dir, OWNER_RWX_ATTR);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create token directory " + dir, e);
        }
    }
}
