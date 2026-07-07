package de.visterion.agora.trading.saxo;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 */
public final class SaxoTokenStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<PosixFilePermission> OWNER_RW =
            PosixFilePermissions.fromString("rw-------");

    private final String connectionId;
    private final Path file;
    private final LongSupplier nowMillis;

    private volatile String accessToken;
    private volatile long accessExpiresAtMillis;
    private volatile long accessTtlMillis;
    private volatile String refreshToken;
    private volatile String deadReason;

    public SaxoTokenStore(String connectionId, Path dir, LongSupplier nowMillis) {
        this.connectionId = connectionId;
        this.file = dir.resolve(connectionId + ".token");
        this.nowMillis = nowMillis;
        loadFile();
    }

    public synchronized void update(String accessToken, long expiresInSeconds, String newRefreshToken) {
        persistRefreshToken(newRefreshToken);            // disk first — crash-safe rolling
        this.refreshToken = newRefreshToken;
        this.accessToken = accessToken;
        this.accessTtlMillis = expiresInSeconds * 1000L;
        this.accessExpiresAtMillis = nowMillis.getAsLong() + this.accessTtlMillis;
        this.deadReason = null;
    }

    public Optional<String> validAccessToken() {
        String t = accessToken;
        if (t == null || nowMillis.getAsLong() >= accessExpiresAtMillis) return Optional.empty();
        return Optional.of(t);
    }

    public boolean hasRefreshToken() { return refreshToken != null; }
    public String refreshToken() { return refreshToken; }
    public long accessTtlMillis() { return accessToken == null ? 0 : accessTtlMillis; }

    public long accessRemainingMillis() {
        if (accessToken == null) return 0;
        return Math.max(0, accessExpiresAtMillis - nowMillis.getAsLong());
    }

    public void clearAccess() { this.accessToken = null; }
    public void markDead(String reason) { this.deadReason = reason; }
    public boolean dead() { return deadReason != null; }
    public String deadReason() { return deadReason; }
    public String connectionId() { return connectionId; }

    private void persistRefreshToken(String token) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            ObjectNode json = MAPPER.createObjectNode();
            json.put("refreshToken", token);
            json.put("obtainedAtMillis", nowMillis.getAsLong());
            Files.writeString(tmp, json.toString());
            Files.setPosixFilePermissions(tmp, OWNER_RW);
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
            if (!rt.isMissingNode() && !rt.isNull()) this.refreshToken = rt.asString(null);
        } catch (Exception e) {
            // corrupt file → treat as unauthorized; re-auth via /auth/saxo/login heals it
        }
    }
}
