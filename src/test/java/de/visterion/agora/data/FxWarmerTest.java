package de.visterion.agora.data;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FxWarmerTest {

    private ListAppender<ILoggingEvent> logs;
    private Logger warmerLogger;

    @BeforeEach void attachLogAppender() {
        warmerLogger = (Logger) LoggerFactory.getLogger(FxWarmer.class);
        logs = new ListAppender<>();
        logs.start();
        warmerLogger.addAppender(logs);
    }

    @AfterEach void detachLogAppender() {
        warmerLogger.detachAppender(logs);
    }

    @Test void warmsEachConfiguredPair() {
        FxService fx = Mockito.mock(FxService.class);
        when(fx.rate(anyString(), anyString())).thenReturn(new FxRate("EUR", "USD", BigDecimal.ONE));
        FxWarmer warmer = new FxWarmer(fx, "EURUSD,GBPUSD");
        warmer.refresh();
        verify(fx).rate("EUR", "USD");
        verify(fx).rate("GBP", "USD");
    }

    @Test void emptyListWarmsNothing() {
        FxService fx = Mockito.mock(FxService.class);
        new FxWarmer(fx, "").refresh();
        verifyNoInteractions(fx);
    }

    @Test void neverThrowsOnServiceFailure() {
        FxService fx = Mockito.mock(FxService.class);
        when(fx.rate(anyString(), anyString()))
                .thenThrow(new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "down", null));
        FxWarmer warmer = new FxWarmer(fx, "EURUSD");
        warmer.refresh(); // must not throw
    }

    @Test void malformedPairLogsWarnNamingTheBadEntry() {
        FxService fx = Mockito.mock(FxService.class);
        FxWarmer warmer = new FxWarmer(fx, "EURUSD,XX,EURGBPX");
        warmer.refresh();
        verify(fx).rate("EUR", "USD");
        assertThat(logs.list)
                .anySatisfy(e -> {
                    assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                    assertThat(e.getFormattedMessage()).contains("XX");
                })
                .anySatisfy(e -> {
                    assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                    assertThat(e.getFormattedMessage()).contains("EURGBPX");
                });
    }
}
