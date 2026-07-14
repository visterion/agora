package de.visterion.agora.observability;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Pushes the Spring-configured provider-logging settings into the static ProviderCallLogger singleton at boot. */
@Component
public class ProviderLoggingConfigurer {

    private final boolean enabled;
    private final int maxBodyChars;

    public ProviderLoggingConfigurer(
            @Value("${agora.provider-logging.enabled:true}") boolean enabled,
            @Value("${agora.provider-logging.max-body-chars:4096}") int maxBodyChars) {
        this.enabled = enabled;
        this.maxBodyChars = maxBodyChars;
    }

    @PostConstruct
    public void init() { ProviderCallLogger.configure(enabled, maxBodyChars); }

    public boolean enabled() { return enabled; }
    public int maxBodyChars() { return maxBodyChars; }
}
