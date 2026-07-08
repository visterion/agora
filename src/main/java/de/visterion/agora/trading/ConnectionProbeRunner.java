package de.visterion.agora.trading;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Probes every active connection once after startup (port already bound). Runs at
 * {@code @Order(100)}, after the Saxo refresher's eager warm-up ({@code @Order(0)}).
 * The outcome is informational and never blocks routing or startup: "ok" on success,
 * "pending" + INFO for a NOT_READY connection (authorized but still warming up), and
 * "unreachable" + WARN for any other failure.
 */
@Component
public class ConnectionProbeRunner {

    private static final Logger log = LoggerFactory.getLogger(ConnectionProbeRunner.class);

    private final ConnectionRegistry registry;

    public ConnectionProbeRunner(ConnectionRegistry registry) {
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    public void probeAll() {
        for (RegisteredConnection c : registry.active()) {
            long t0 = System.nanoTime();
            try {
                c.provider().probe();
                long ms = (System.nanoTime() - t0) / 1_000_000;
                c.setProbeStatus(ProbeStatus.ok(Instant.now()));
                log.info("Connection '{}' probe OK ({}ms)", c.id(), ms);
            } catch (BrokerException e) {
                if (e.kind() == BrokerException.Kind.NOT_READY) {
                    c.setProbeStatus(ProbeStatus.pending(Instant.now(), e.getMessage()));
                    log.info("Connection '{}' probe pending: {}", c.id(), e.getMessage());
                } else {
                    c.setProbeStatus(ProbeStatus.unreachable(Instant.now(), e.getMessage()));
                    log.warn("Connection '{}' probe FAILED: {}", c.id(), e.getMessage());
                }
            } catch (Exception e) {
                c.setProbeStatus(ProbeStatus.unreachable(Instant.now(), e.getMessage()));
                log.warn("Connection '{}' probe FAILED: {}", c.id(), e.getMessage());
            }
        }
    }
}
