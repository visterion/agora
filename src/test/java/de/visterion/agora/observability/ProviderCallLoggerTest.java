package de.visterion.agora.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderCallLoggerTest {

    ListAppender<ILoggingEvent> appender;
    Logger logger;

    @BeforeEach
    void setUp() {
        ProviderCallLogger.configure(true, 4096);
        logger = (Logger) LoggerFactory.getLogger("agora.providercall");
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() { logger.detachAppender(appender); }

    private ClientHttpResponse exec(MockClientHttpRequest req, MockClientHttpResponse resp) throws Exception {
        return ProviderCallLogger.INSTANCE.intercept(req, new byte[0], (r, b) -> resp);
    }

    @Test
    void logsStructuredLineWithProviderStatusDuration() throws Exception {
        MockClientHttpRequest req = new MockClientHttpRequest(org.springframework.http.HttpMethod.GET,
                URI.create("https://query1.finance.yahoo.com/v8/finance/chart/SAP.DE?range=1d"));
        MockClientHttpResponse resp = new MockClientHttpResponse("{\"ok\":true}".getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
        exec(req, resp);
        assertThat(appender.list).hasSize(1);
        String line = appender.list.get(0).getFormattedMessage();
        assertThat(line).startsWith("provider_call").contains("provider=yahoo")
                .contains("method=GET").contains("status=200").contains("dur_ms=").contains("symbol=SAP.DE");
    }

    @Test
    void redactsSecretHeaderAndCapsBody() throws Exception {
        ProviderCallLogger.configure(true, 8);
        MockClientHttpRequest req = new MockClientHttpRequest(org.springframework.http.HttpMethod.GET,
                URI.create("https://finnhub.io/api/v1/quote?symbol=AAPL"));
        req.getHeaders().add("X-Finnhub-Token", "SEKRET");
        MockClientHttpResponse resp = new MockClientHttpResponse("0123456789ABCDEF".getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
        exec(req, resp);
        String line = appender.list.get(0).getFormattedMessage();
        assertThat(line).doesNotContain("SEKRET");
        assertThat(line).contains("X-Finnhub-Token=***");
        assertThat(line).contains("resp_bytes=16");     // original size
        assertThat(line).contains("[+8b]");              // 16 - 8 = 8 dropped
    }

    @Test
    void responseBodyStillReadableByConsumer() throws Exception {
        MockClientHttpRequest req = new MockClientHttpRequest(org.springframework.http.HttpMethod.GET,
                URI.create("https://data.sec.gov/submissions/CIK0000320193.json"));
        MockClientHttpResponse resp = new MockClientHttpResponse("BODY".getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
        ClientHttpResponse out = exec(req, resp);
        String consumed = new String(out.getBody().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(consumed).isEqualTo("BODY");        // buffering did not eat the stream
        assertThat(appender.list.get(0).getFormattedMessage()).contains("provider=edgar");
    }

    @Test
    void postEmitFailureStillReturnsReadableBody() throws Exception {
        MockClientHttpRequest req = new MockClientHttpRequest(org.springframework.http.HttpMethod.GET,
                URI.create("https://data.sec.gov/submissions/CIK0000320193.json"));
        byte[] respBytes = "FULL BODY".getBytes(StandardCharsets.UTF_8);
        ClientHttpResponse throwingResp = new MockClientHttpResponse(respBytes, HttpStatus.OK) {
            @Override
            public org.springframework.http.HttpStatusCode getStatusCode() {
                throw new IllegalStateException("boom during emit");
            }
        };
        ClientHttpResponse out = ProviderCallLogger.INSTANCE.intercept(req, new byte[0], (r, b) -> throwingResp);
        String consumed = new String(out.getBody().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(consumed).isEqualTo("FULL BODY");   // buffered bytes served despite emit failure
        // emit failed before producing a provider_call INFO line; only the WARN fallback was logged
        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.INFO);
        assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("provider_call logging failed"));
    }

    @Test
    void capReportsCorrectDropCountWhenBodyAlsoRedacted() throws Exception {
        ProviderCallLogger.configure(true, 20);
        MockClientHttpRequest req = new MockClientHttpRequest(org.springframework.http.HttpMethod.GET,
                URI.create("https://finnhub.io/api/v1/quote?symbol=AAPL"));
        // "token" gets redacted to "***" (post-redaction string is what gets capped)
        String respBody = "{\"token\":\"abcdefghijklmnopqrstuvwxyz\",\"extra\":\"padding-padding-padding\"}";
        MockClientHttpResponse resp = new MockClientHttpResponse(respBody.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
        exec(req, resp);
        String line = appender.list.get(0).getFormattedMessage();
        String redacted = ProviderLogRedactor.redactBody(respBody);
        int max = 20;
        int totalBytes = redacted.getBytes(StandardCharsets.UTF_8).length;
        int keptBytes = redacted.substring(0, max).getBytes(StandardCharsets.UTF_8).length;
        int expectedDropped = totalBytes - keptBytes;
        assertThat(line).contains("[+" + expectedDropped + "b]");
        // sanity: this must NOT equal a naive pre-redaction-baseline calculation
        int wrongDropped = respBody.getBytes(StandardCharsets.UTF_8).length - keptBytes;
        assertThat(expectedDropped).isNotEqualTo(wrongDropped);
    }

    @Test
    void disabledIsPassThroughNoLog() throws Exception {
        ProviderCallLogger.configure(false, 4096);
        MockClientHttpRequest req = new MockClientHttpRequest(org.springframework.http.HttpMethod.GET,
                URI.create("https://query1.finance.yahoo.com/x"));
        MockClientHttpResponse resp = new MockClientHttpResponse("X".getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
        ClientHttpResponse out = exec(req, resp);
        assertThat(appender.list).isEmpty();
        assertThat(new String(out.getBody().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("X");
    }
}
