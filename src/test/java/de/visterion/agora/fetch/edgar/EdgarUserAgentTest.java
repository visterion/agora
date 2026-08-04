package de.visterion.agora.fetch.edgar;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the loud-not-silent handling of a missing EDGAR contact User-Agent
 * (AGORA_DATA_EDGAR_USER_AGENT). The YAML default was blanked out (a real contact address must
 * never be committed to this public repo) but SEC rejects every EDGAR request with HTTP 403
 * without one — roughly 345 successful calls per production run depend on it — so a blank value
 * must surface as a clear WARN, never a silent gap and never a thrown exception.
 */
class EdgarUserAgentTest {

    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach void attachLogAppender() {
        logger = (Logger) LoggerFactory.getLogger(EdgarUserAgent.class);
        logger.setLevel(Level.WARN);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach void detachLogAppender() {
        logger.detachAppender(logs);
    }

    private java.util.List<ILoggingEvent> warnEvents() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    @Test void blankConfiguredValueLogsWarnNamingTheEnvVarAndConsequence() {
        EdgarUserAgent.checked(" ");

        assertThat(warnEvents()).hasSize(1);
        assertThat(warnEvents().get(0).getFormattedMessage())
                .contains("AGORA_DATA_EDGAR_USER_AGENT")
                .contains("403");
    }

    @Test void nullConfiguredValueLogsWarn() {
        EdgarUserAgent.checked(null);

        assertThat(warnEvents()).hasSize(1);
    }

    @Test void nonBlankConfiguredValueProducesNoWarn() {
        EdgarUserAgent.checked("SyntheticApp contact@example.com");

        assertThat(warnEvents()).isEmpty();
    }

    @Test void checkedReturnsTheValueUnchanged() {
        assertThat(EdgarUserAgent.checked("SyntheticApp contact@example.com"))
                .isEqualTo("SyntheticApp contact@example.com");
        assertThat(EdgarUserAgent.checked(null)).isNull();
    }

    // ---- the same guard, exercised through each of the three real @Autowired constructors ----

    @Test void edgarSearchServiceConstructorWarnsOnBlankUserAgent() {
        new EdgarSearchService(" ", "https://efts.sec.gov", "https://www.sec.gov", 3600L, 15000L,
                32L * 1024 * 1024, 8, new EdgarCikResolver("SyntheticApp contact@example.com", 15000L));

        assertThat(warnEvents()).hasSize(1);
    }

    @Test void edgarSearchServiceConstructorIsQuietOnConfiguredUserAgent() {
        new EdgarSearchService("SyntheticApp contact@example.com", "https://efts.sec.gov",
                "https://www.sec.gov", 3600L, 15000L, 32L * 1024 * 1024, 8,
                new EdgarCikResolver("SyntheticApp contact@example.com", 15000L));

        assertThat(warnEvents()).isEmpty();
    }

    @Test void edgarCikResolverConstructorWarnsOnBlankUserAgent() {
        new EdgarCikResolver(" ", 15000L);

        assertThat(warnEvents()).hasSize(1);
    }

    @Test void edgarCikResolverConstructorIsQuietOnConfiguredUserAgent() {
        new EdgarCikResolver("SyntheticApp contact@example.com", 15000L);

        assertThat(warnEvents()).isEmpty();
    }

    @Test void edgarServiceConstructorWarnsOnBlankUserAgent() {
        EdgarCikResolver cikResolver = new EdgarCikResolver(RestClient.builder().baseUrl("https://www.sec.gov").build());

        new EdgarService(" ", cikResolver, 3600L, 15000L);

        assertThat(warnEvents()).hasSize(1);
    }

    @Test void edgarServiceConstructorIsQuietOnConfiguredUserAgent() {
        EdgarCikResolver cikResolver = new EdgarCikResolver(RestClient.builder().baseUrl("https://www.sec.gov").build());

        new EdgarService("SyntheticApp contact@example.com", cikResolver, 3600L, 15000L);

        assertThat(warnEvents()).isEmpty();
    }
}
