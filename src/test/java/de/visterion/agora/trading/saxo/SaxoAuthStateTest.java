package de.visterion.agora.trading.saxo;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.assertThat;

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
}
