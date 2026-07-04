package de.visterion.agora.data;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.annotation.UserConfigurations;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves FxWarmer's {@code @ConditionalOnProperty} gating: it is only a bean when
 * {@code agora.data.fx.refresh.enabled=true}. Uses ApplicationContextRunner (not a full
 * @SpringBootTest) so the condition is evaluated cheaply against the class registered as a
 * config candidate via UserConfigurations.of(...).
 */
class FxWarmerWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(FxService.class, () -> Mockito.mock(FxService.class))
            .withConfiguration(UserConfigurations.of(FxWarmer.class))
            .withPropertyValues("agora.data.fx.warm-pairs=EURUSD");

    @Test void notWiredWhenPropertyAbsent() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(FxWarmer.class));
    }

    @Test void notWiredWhenPropertyFalse() {
        runner.withPropertyValues("agora.data.fx.refresh.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(FxWarmer.class));
    }

    @Test void wiredWhenPropertyTrue() {
        runner.withPropertyValues("agora.data.fx.refresh.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(FxWarmer.class));
    }
}
