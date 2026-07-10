package de.visterion.agora.trading.saxo;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SaxoAuthStateTest {

    final AtomicLong now = new AtomicLong(0);
    final SaxoAuthState states = new SaxoAuthState(now::get);

    @Test
    void issuedStateConsumesOnceForItsConnection() {
        String s = states.issue("saxo-sim");
        assertThat(s).hasSize(32).matches("[0-9a-f]+");
        assertThat(states.consume(s)).contains("saxo-sim");
        assertThat(states.consume(s)).isEmpty();            // one-shot
    }

    @Test
    void unknownStateIsRejected() {
        assertThat(states.consume("deadbeef")).isEmpty();
    }

    @Test
    void expiredStateIsRejected() {
        String s = states.issue("saxo-sim");
        now.addAndGet(5 * 60 * 1000L + 1);
        assertThat(states.consume(s)).isEmpty();
    }

    @Test
    void statesAreUnique() {
        assertThat(states.issue("a")).isNotEqualTo(states.issue("a"));
    }

    @Test
    void expiredStatesAreSweptOnIssue() {
        String old1 = states.issue("a");
        String old2 = states.issue("a");
        now.addAndGet(5 * 60 * 1000L + 1);
        states.issue("a");                       // triggers sweep
        assertThat(states.consume(old1)).isEmpty();
        assertThat(states.consume(old2)).isEmpty();
    }

    @Test
    void issueRejectsWhenAtCapacityWithAllLiveStatesAndNeverEvictsThem() {
        List<String> issued = new ArrayList<>();
        for (int i = 0; i < 1000; i++) issued.add(states.issue("a"));

        assertThatThrownBy(() -> states.issue("a"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("too many pending authorizations");

        // the earliest-issued live state (what the old smallest-expiresAt eviction
        // would have dropped) must still be resolvable — no live state was evicted.
        assertThat(states.consume(issued.get(0))).contains("a");
    }

    @Test
    void issueSucceedsAfterSweepingExpiredEntriesFreesCapacity() {
        for (int i = 0; i < 999; i++) states.issue("a");
        now.addAndGet(5 * 60 * 1000L + 1);   // all 999 now expired

        String s = states.issue("a");        // sweep clears them, well under cap

        assertThat(states.consume(s)).contains("a");
    }
}
