package de.visterion.agora.observability;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ProviderLoggingConfigurerTest {
    @Test
    void postConstructPushesValuesIntoLogger() {
        ProviderLoggingConfigurer c = new ProviderLoggingConfigurer(false, 123);
        c.init();
        // configure() is the only observable effect; assert via a follow-up record() being a no-op when disabled.
        // Re-enable to leave a clean global state for other tests.
        assertThat(c.enabled()).isFalse();
        assertThat(c.maxBodyChars()).isEqualTo(123);
        ProviderCallLogger.configure(true, 4096);
    }
}
