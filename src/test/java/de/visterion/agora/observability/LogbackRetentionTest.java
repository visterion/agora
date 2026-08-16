package de.visterion.agora.observability;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.util.FileSize;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the file-based log retention contract in {@code logback-spring.xml}. The 14-day
 * window is not observable on day one — a production check would take a fortnight — so
 * it is asserted here against the parsed configuration instead of being believed.
 */
class LogbackRetentionTest {

    /**
     * Loads {@code logback-spring.xml} by resource name via Joran, so it actually starts
     * the FILE appender for real (unlike the app's own {@code logback-test.xml}, which
     * only shadows Spring Boot's normal config lookup and does not apply here). To keep
     * that appender from ever touching a real production log directory, the
     * {@code AGORA_LOG_DIR} property is pinned directly on this throwaway
     * {@link LoggerContext} to the test's own {@code @TempDir} *before* configuring —
     * this overrides whatever {@code AGORA_LOG_DIR} happens to be set to in the actual
     * process environment.
     */
    private LoggerContext configure(Path logDir) throws Exception {
        LoggerContext context = new LoggerContext();
        context.putProperty("AGORA_LOG_DIR", logDir.toString());
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(
                getClass().getClassLoader().getResourceAsStream("logback-spring.xml"));
        return context;
    }

    /**
     * Neither {@code totalSizeCap} nor {@code maxFileSize} has a public getter on
     * {@link SizeAndTimeBasedRollingPolicy} / its {@code TimeBasedRollingPolicy} parent,
     * so this reads the (protected/package-private) field directly.
     */
    private static FileSize readFileSizeField(Class<?> declaringClass, Object target, String fieldName)
            throws Exception {
        Field field = declaringClass.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (FileSize) field.get(target);
    }

    @Test
    void keepsFourteenDaysOfDailyFiles(@TempDir Path logDir) throws Exception {
        LoggerContext context = configure(logDir);

        var appender = (RollingFileAppender<?>) context.getLogger("ROOT").getAppender("FILE");
        assertThat(appender).as("appender FILE must exist").isNotNull();

        var policy = (SizeAndTimeBasedRollingPolicy<?>) appender.getRollingPolicy();
        assertThat(policy.getMaxHistory()).isEqualTo(14);
        // Daily, gzipped, with a size-driven %i index within each day.
        assertThat(policy.getFileNamePattern())
                .contains("%d{yyyy-MM-dd}")
                .contains("%i")
                .endsWith(".log.gz");
        // Pinned so a "tidy up the cap" edit can't silently drop retention below 14
        // days (totalSizeCap governs the archives) or let the active file grow
        // unbounded until midnight (maxFileSize governs today's agora.log).
        FileSize totalSizeCap = readFileSizeField(
                SizeAndTimeBasedRollingPolicy.class.getSuperclass(), policy, "totalSizeCap");
        assertThat(totalSizeCap.getSize()).isEqualTo(FileSize.valueOf("10GB").getSize());
        FileSize maxFileSize = readFileSizeField(
                SizeAndTimeBasedRollingPolicy.class, policy, "maxFileSize");
        assertThat(maxFileSize.getSize()).isEqualTo(FileSize.valueOf("100MB").getSize());
    }

    @Test
    void keepsTheConsoleAppenderSoDockerLogsStillWorks(@TempDir Path logDir) throws Exception {
        LoggerContext context = configure(logDir);

        // The evening analysis reads `docker logs` for the provider_call lines.
        // Replacing the console appender instead of adding to it would blind that
        // analysis — this assertion is the guard against that.
        assertThat(context.getLogger("ROOT").getAppender("CONSOLE"))
                .as("appender CONSOLE must survive").isNotNull();
    }
}
