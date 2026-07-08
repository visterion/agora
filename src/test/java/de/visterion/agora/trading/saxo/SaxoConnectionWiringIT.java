package de.visterion.agora.trading.saxo;

import de.visterion.agora.trading.ConnectionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves a configured saxo slot activates through the real registry + factory. */
@SpringBootTest(properties = {
        "agora.trading.connections.saxo-sim.provider=saxo",
        "agora.trading.connections.saxo-sim.environment=paper",
        "agora.trading.connections.saxo-sim.base-url=http://localhost:1",
        "agora.trading.connections.saxo-sim.key-id=app-key",
        "agora.trading.connections.saxo-sim.secret=app-secret",
        "agora.trading.saxo.token-dir=${java.io.tmpdir}/agora-saxo-it-tokens"
})
class SaxoConnectionWiringIT {

    @Autowired ConnectionRegistry registry;

    @Test
    void saxoSlotActivatesWithSaxoProvider() {
        var rc = registry.get("saxo-sim");
        assertThat(rc).isPresent();
        assertThat(rc.get().provider()).isInstanceOf(SaxoBrokerProvider.class);
        assertThat(rc.get().provider().name()).isEqualTo("saxo");
    }
}
