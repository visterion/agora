package de.visterion.agora.trading.saxo;

import de.visterion.agora.trading.BrokerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Token state for ONE Saxo connection. Only the (rolling) refresh token is persisted —
 * the short-lived access token lives in memory. Persistence is attempted BEFORE the
 * in-memory swap (so a successful persist is never silently lost), but a persist
 * failure does NOT abort the update (M-T8): the in-memory swap happens regardless,
 * because a live session carried only in memory is strictly better than a dead one —
 * disk (read-only volume, full disk, ...) is an availability concern, not a correctness
 * one, and the next successful persist will catch the file up. File perms are
 * owner-only, re-asserted on every persist (not just at creation); token values are
 * never logged.
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
        applyUpdate(accessToken, expiresInSeconds, newRefreshToken);
    }

    /**
     * H7 CAS: applies the update only if the store's current refresh token still equals
     * {@code expectedRefreshToken} — the caller captures that token before an in-flight
     * network refresh so a concurrent {@code /auth/saxo/callback} (human re-authorizing)
     * can't be clobbered by a stale result landing afterwards. Returns whether it applied.
     */
    public synchronized boolean updateIfCurrent(String expectedRefreshToken, String accessToken,
            long expiresInSeconds, String newRefreshToken) {
        if (!Objects.equals(expectedRefreshToken, state.refreshToken())) {
            return false;
        }
        applyUpdate(accessToken, expiresInSeconds, newRefreshToken);
        return true;
    }

    private void applyUpdate(String accessToken, long expiresInSeconds, String newRefreshToken) {
        // C6: Saxo may legally omit refresh_token on a refresh response (RFC 6749 §6) —
        // that means "keep the one you have", not "the session no longer has one".
        if (newRefreshToken == null || newRefreshToken.isBlank()) {
            newRefreshToken = state.refreshToken();
        }
        // M-T8: persist first so a successful write is never lost, but a persist failure
        // must not prevent the in-memory swap — see class-level comment.
        try {
            persistRefreshToken(newRefreshToken);
        } catch (UncheckedIOException e) {
            log.error("Saxo token persist failed for '{}' — session survives in memory only: {}",
                    connectionId, e.getMessage());
        }
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

    /**
     * H7 CAS counterpart to {@link #markDead(String)}: only marks dead if the store's
     * current refresh token still equals {@code expectedRefreshToken}. Returns whether it
     * applied — the caller must not treat a false return as "session is fine", but also
     * must not overwrite whatever the concurrent winner (a fresh authorize) just set.
     */
    public synchronized boolean markDeadIfCurrent(String expectedRefreshToken, String reason) {
        TokenState s = this.state;
        if (!Objects.equals(expectedRefreshToken, s.refreshToken())) {
            return false;
        }
        this.state = new TokenState(
                s.accessToken(), s.accessExpiresAtMillis(), s.accessTtlMillis(), s.refreshToken(), reason);
        return true;
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
            byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
            // fsync the tmp file's content before the atomic move so a crash right after
            // rename can never observe a file whose bytes didn't actually make it to disk.
            try (FileChannel ch = FileChannel.open(tmp,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ch.write(ByteBuffer.wrap(bytes));
                ch.force(true);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            // re-assert owner-only perms on every persist, not only at file creation — a
            // REPLACE_EXISTING move can otherwise inherit/keep looser perms from a prior file.
            Files.setPosixFilePermissions(file, OWNER_RW);
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
            // Files.createDirectories is a no-op on an already-existing dir and does NOT
            // reset its perms — re-assert owner-only every call, not only at first creation.
            Files.setPosixFilePermissions(dir, OWNER_RWX);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create token directory " + dir, e);
        }
    }
}
