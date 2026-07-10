package de.visterion.agora.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Optional background warmer for a STATIC, configured set of FX pairs. Agora never learns which
 * currencies a consumer cares about — that stays in the consumer. Empty list = no warming.
 */
@Component
@ConditionalOnProperty(value = "agora.data.fx.refresh.enabled", havingValue = "true")
public class FxWarmer {

    private static final Logger log = LoggerFactory.getLogger(FxWarmer.class);

    private final FxService fx;
    private final String warmPairs;

    public FxWarmer(FxService fx, @Value("${agora.data.fx.warm-pairs:}") String warmPairs) {
        this.fx = fx;
        this.warmPairs = warmPairs;
    }

    @Scheduled(initialDelayString = "${agora.data.fx.refresh.initial-delay-ms:0}",
               fixedDelayString = "${agora.data.fx.refresh.fixed-delay-ms:1800000}")
    public void refresh() {
        if (warmPairs == null || warmPairs.isBlank()) return;
        for (String raw : warmPairs.split(",")) {
            String pair = raw.strip().toUpperCase();
            if (pair.length() != 6) {
                log.warn("FX warm skipped malformed pair '{}' (expected 6 letters, e.g. EURUSD)", raw.strip());
                continue;
            }
            try {
                fx.rate(pair.substring(0, 3), pair.substring(3, 6));
            } catch (RuntimeException e) {
                log.warn("FX warm failed for {}: {}", pair, e.getMessage());
            }
        }
    }
}
