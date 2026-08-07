package de.visterion.agora.data;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A provider that failed on the way to a successful fallback must leave a trace.
 *
 * <p>{@code firstSuccess} swallowed every typed {@link MarketDataException} without a word, so
 * "Alpaca failed, Yahoo answered" was invisible in the chain. {@code ProviderCallLogger} covers the
 * HTTP-visible half at INFO but structurally cannot see a read/connect timeout (it throws before
 * that interceptor's emit), a keyless self-skip (no HTTP call at all), or the chain decision itself.
 *
 * <p>Level is DEBUG on purpose and this test pins that: keyless TwelveData/Finnhub self-skip with
 * {@code UNAVAILABLE} on every single call, so a WARN here would fire on every quote of a healthy
 * deployment.
 */
class MarketDataServiceFallbackTraceTest {

    private ListAppender<ILoggingEvent> logs;
    private Logger serviceLogger;

    @BeforeEach void attachLogAppender() {
        serviceLogger = (Logger) LoggerFactory.getLogger(MarketDataService.class);
        serviceLogger.setLevel(Level.DEBUG);
        logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach void detachLogAppender() {
        serviceLogger.detachAppender(logs);
    }

    private static MarketDataProvider failing(String name, MarketDataException.Kind kind) {
        return new MarketDataProvider() {
            public String name() { return name; }
            public Quote quote(String s) { throw new MarketDataException(kind, name + " is down", null); }
            public List<OhlcBar> ohlc(String s, int d) { throw new MarketDataException(kind, name + " is down", null); }
        };
    }

    private static MarketDataProvider healthy(String name) {
        return new MarketDataProvider() {
            public String name() { return name; }
            public Quote quote(String s) { return new Quote(s, new BigDecimal("7.25"), BigDecimal.ZERO, "USD"); }
            public List<OhlcBar> ohlc(String s, int d) { return List.of(); }
        };
    }

    private List<ILoggingEvent> events() { return List.copyOf(logs.list); }

    @Test void aFailedProviderOnTheWayToASuccessfulFallbackIsTraced() {
        var svc = new MarketDataService(
                List.of(failing("alpaca", MarketDataException.Kind.UNAVAILABLE), healthy("yahoo")), 120L);

        assertThat(svc.quote("SYNA").price()).isEqualByComparingTo("7.25");

        assertThat(events())
                .as("the failure of alpaca must not vanish just because yahoo answered")
                .anySatisfy(e -> {
                    assertThat(e.getFormattedMessage()).contains("alpaca").contains("quote SYNA")
                            .contains("UNAVAILABLE");
                    assertThat(e.getLevel()).isEqualTo(Level.DEBUG);
                });
    }

    @Test void theChainOutcomeNamesTheProviderThatActuallyServed() {
        var svc = new MarketDataService(
                List.of(failing("alpaca", MarketDataException.Kind.UNAVAILABLE),
                        failing("twelvedata", MarketDataException.Kind.RATE_LIMITED),
                        healthy("yahoo")), 120L);

        svc.quote("SYNA");

        assertThat(events()).anySatisfy(e -> {
            assertThat(e.getFormattedMessage())
                    .contains("served by yahoo")
                    .contains("alpaca(UNAVAILABLE)")
                    .contains("twelvedata(RATE_LIMITED)");
            assertThat(e.getLevel()).isEqualTo(Level.DEBUG);
        });
    }

    @Test void aFirstProviderSuccessLogsNoChainLine() {
        var svc = new MarketDataService(List.of(healthy("alpaca")), 120L);

        svc.quote("SYNA");

        assertThat(events())
                .as("the happy path must stay silent — this runs on every quote and every ohlc fetch")
                .isEmpty();
    }

    /** The volume guard: nothing this path emits may reach WARN, or a keyless deployment warns
     *  on every single call. Unexpected RuntimeExceptions keep their own WARN and are not here. */
    @Test void theChainTraceNeverReachesWarn() {
        var svc = new MarketDataService(
                List.of(failing("alpaca", MarketDataException.Kind.UNAVAILABLE),
                        failing("finnhub", MarketDataException.Kind.UNAVAILABLE),
                        healthy("yahoo")), 120L);

        svc.ohlc("SYNA", 30);

        assertThat(events()).isNotEmpty();
        assertThat(events()).allSatisfy(e ->
                assertThat(e.getLevel().toInt()).isLessThan(Level.WARN.toInt()));
    }
}
