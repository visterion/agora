package de.visterion.agora.trading;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ProbeStatusTest {

    @Test
    void pendingHasPendingStateAndKeepsDetail() {
        Instant at = Instant.ofEpochMilli(1_000_000L);
        ProbeStatus s = ProbeStatus.pending(at, "token refresh pending");
        assertThat(s.state()).isEqualTo("pending");
        assertThat(s.probedAt()).isEqualTo(at);
        assertThat(s.detail()).isEqualTo("token refresh pending");
    }

    @Test
    void notReadyKindExists() {
        assertThat(BrokerException.Kind.valueOf("NOT_READY"))
                .isEqualTo(BrokerException.Kind.NOT_READY);
    }
}
