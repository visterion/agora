package de.visterion.agora.trading;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LiveAccessGuardTest {

    private static RegisteredConnection conn(ConnectionConfig.Environment env) {
        ConnectionConfig cfg = new ConnectionConfig();
        cfg.setProvider("stub");
        cfg.setEnvironment(env);
        cfg.setKeyId("k");
        cfg.setSecret("s");
        return new RegisteredConnection("c", cfg, null);
    }

    @Test
    void liveTokenHasLiveAccess() {
        var guard = new LiveAccessGuard(Set.of("live-1"), () -> "live-1");
        assertThat(guard.hasLiveAccess()).isTrue();
        assertThat(guard.canSee(conn(ConnectionConfig.Environment.LIVE))).isTrue();
    }

    @Test
    void nonLiveTokenCannotSeeLiveConnection() {
        var guard = new LiveAccessGuard(Set.of("live-1"), () -> "trade-1");
        assertThat(guard.hasLiveAccess()).isFalse();
        assertThat(guard.canSee(conn(ConnectionConfig.Environment.LIVE))).isFalse();
        assertThat(guard.canSee(conn(ConnectionConfig.Environment.PAPER))).isTrue();
    }

    @Test
    void nullTokenMeansNoLiveAccess() {
        var guard = new LiveAccessGuard(Set.of("live-1"), () -> null);
        assertThat(guard.hasLiveAccess()).isFalse();
        assertThat(guard.canSee(conn(ConnectionConfig.Environment.PAPER))).isTrue();
    }

    @Test
    void emptyLiveSetNeverGrantsAccess() {
        var guard = new LiveAccessGuard(Set.of(), () -> "anything");
        assertThat(guard.hasLiveAccess()).isFalse();
    }
}
