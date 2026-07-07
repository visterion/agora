package de.visterion.agora.trading.saxo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class SaxoTokenStoreTest {

    @TempDir Path dir;
    final AtomicLong now = new AtomicLong(1_000_000L);

    private SaxoTokenStore store(String id) { return new SaxoTokenStore(id, dir, now::get); }

    @Test
    void freshStoreIsUnauthorized() {
        var s = store("saxo-sim");
        assertThat(s.validAccessToken()).isEmpty();
        assertThat(s.hasRefreshToken()).isFalse();
        assertThat(s.dead()).isFalse();
    }

    @Test
    void updateMakesAccessTokenValidUntilExpiry() {
        var s = store("saxo-sim");
        s.update("acc-1", 1200, "ref-1");
        assertThat(s.validAccessToken()).contains("acc-1");
        assertThat(s.accessTtlMillis()).isEqualTo(1_200_000L);
        now.addAndGet(1_199_000L);
        assertThat(s.validAccessToken()).contains("acc-1");
        now.addAndGet(2_000L);
        assertThat(s.validAccessToken()).isEmpty();      // expired
        assertThat(s.hasRefreshToken()).isTrue();        // refresh survives
    }

    @Test
    void refreshTokenIsPersistedAndReloadedAcrossRestart() {
        store("saxo-sim").update("acc-1", 1200, "ref-rolled");
        var reloaded = store("saxo-sim");                // simulates restart
        assertThat(reloaded.hasRefreshToken()).isTrue();
        assertThat(reloaded.refreshToken()).isEqualTo("ref-rolled");
        assertThat(reloaded.validAccessToken()).isEmpty(); // access never persisted
    }

    @Test
    void tokenFileHasOwnerOnlyPermissions() throws Exception {
        store("saxo-sim").update("acc-1", 1200, "ref-1");
        Path f = dir.resolve("saxo-sim.token");
        assertThat(f).exists();
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(f);
        assertThat(perms).containsExactlyInAnyOrder(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        assertThat(Files.readString(f)).doesNotContain("acc-1"); // access token never on disk
    }

    @Test
    void updateLeavesNoTmpFileBehind() throws Exception {
        var s = store("saxo-sim");
        s.update("acc-1", 1200, "ref-1");
        s.update("acc-2", 1200, "ref-2");
        Path tmp = dir.resolve("saxo-sim.token.tmp");
        assertThat(Files.exists(tmp)).isFalse();
    }

    @Test
    void updateOverwritesPreviousRefreshToken() {
        var s = store("saxo-sim");
        s.update("acc-1", 1200, "ref-1");
        s.update("acc-2", 1200, "ref-2");
        assertThat(store("saxo-sim").refreshToken()).isEqualTo("ref-2");
    }

    @Test
    void markDeadIsClearedByUpdate() {
        var s = store("saxo-sim");
        s.update("acc-1", 1200, "ref-1");
        s.markDead("invalid_grant");
        assertThat(s.dead()).isTrue();
        assertThat(s.deadReason()).isEqualTo("invalid_grant");
        s.update("acc-2", 1200, "ref-2");
        assertThat(s.dead()).isFalse();
    }

    @Test
    void corruptTokenFileIsTreatedAsUnauthorized() throws Exception {
        Files.writeString(dir.resolve("saxo-sim.token"), "not-json{{{");
        var s = store("saxo-sim");
        assertThat(s.hasRefreshToken()).isFalse();
        assertThat(s.validAccessToken()).isEmpty();
    }

    @Test
    void storesReturnsSameInstancePerConnection() {
        var stores = new SaxoTokenStores(dir, now::get);
        assertThat(stores.forConnection("a")).isSameAs(stores.forConnection("a"));
        assertThat(stores.forConnection("a")).isNotSameAs(stores.forConnection("b"));
    }
}
