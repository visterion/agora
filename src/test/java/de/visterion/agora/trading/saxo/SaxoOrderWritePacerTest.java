package de.visterion.agora.trading.saxo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Saxo documents ONE order operation per second per session. Every test here pins a piece of
 * that policy: the pacer waits (it never throws — a throw would turn a client-side queue into
 * a broker failure and would block the naked-entry fail-safe with the very condition that
 * triggered it), it only touches order WRITES, and any 429 feedback is clamped.
 */
class SaxoOrderWritePacerTest {

    /** Prod SIM/LIVE base URLs: the RestClient carries the base URL, so the interceptor sees the
     *  ABSOLUTE uri. A startsWith("/trade/v2/orders") predicate would match nothing in prod. */
    private static final String SIM = "https://gateway.saxobank.com/sim/openapi";
    private static final String LIVE = "https://gateway.saxobank.com/openapi";
    private static final String ORDERS = "/trade/v2/orders";

    private final AtomicLong clock = new AtomicLong(0L);
    private final List<Long> sleeps = new ArrayList<>();
    private final List<Long> releasedAt = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted(); // never leak an interrupt into the next test
    }

    private SaxoOrderWritePacer pacer(long minIntervalMs) {
        return new SaxoOrderWritePacer(minIntervalMs, 3_000L, 1_000L, clock::get,
                ms -> { sleeps.add(ms); clock.addAndGet(ms); });
    }

    private ClientHttpResponse send(SaxoOrderWritePacer pacer, HttpMethod method, String url)
            throws IOException {
        return send(pacer, method, url, 200, new HttpHeaders());
    }

    private ClientHttpResponse send(SaxoOrderWritePacer pacer, HttpMethod method, String url,
                                    int status, HttpHeaders responseHeaders) throws IOException {
        var request = new MockClientHttpRequest(method, URI.create(url));
        ClientHttpRequestExecution execution = (req, body) -> {
            releasedAt.add(clock.get());
            var response = new MockClientHttpResponse(new byte[0], status);
            response.getHeaders().putAll(responseHeaders);
            return response;
        };
        return pacer.intercept(request, new byte[0], execution);
    }

    private static HttpHeaders headers(String name, String value) {
        var h = new HttpHeaders();
        h.add(name, value);
        return h;
    }

    // ---- (a) which requests are paced ----

    @Test
    void patchPostAndDeleteOnTheOrderPathAreAllPaced() throws Exception {
        var pacer = pacer(1_100L);

        send(pacer, HttpMethod.PATCH, SIM + ORDERS);
        send(pacer, HttpMethod.POST, LIVE + ORDERS);
        send(pacer, HttpMethod.DELETE, SIM + ORDERS + "/123");

        assertThat(releasedAt).containsExactly(0L, 1_100L, 2_200L);
    }

    // ---- (b) which requests are not ----

    @Test
    void readsAndNonOrderPathsAreNeverPaced() throws Exception {
        var pacer = pacer(1_100L);

        send(pacer, HttpMethod.GET, SIM + ORDERS);
        send(pacer, HttpMethod.GET, SIM + "/port/v1/orders/me");
        send(pacer, HttpMethod.POST, SIM + "/port/v1/orders/me");

        assertThat(releasedAt).containsExactly(0L, 0L, 0L);
        assertThat(sleeps).isEmpty();
    }

    // ---- (c) spacing is measured from the previous RELEASE ----

    @Test
    void aWriteArriving100msLaterIsReleased1100msAfterTheFirstRelease() throws Exception {
        var pacer = pacer(1_100L);

        send(pacer, HttpMethod.PATCH, SIM + ORDERS);   // released at t=0
        clock.addAndGet(100L);                          // caller arrives 100 ms later
        send(pacer, HttpMethod.PATCH, SIM + ORDERS);

        assertThat(releasedAt).containsExactly(0L, 1_100L);
        assertThat(sleeps).containsExactly(1_000L);
    }

    // ---- (d) 429 feedback, clamped, one header dimension only ----

    @Test
    void sessionOrdersResetHeaderBlocksTheNextWriteBeyondTheMinimumInterval() throws Exception {
        var pacer = pacer(1_100L);

        send(pacer, HttpMethod.PATCH, SIM + ORDERS, 429,
                headers(SaxoOrderWritePacer.SESSION_ORDERS_RESET, "2"));
        send(pacer, HttpMethod.PATCH, SIM + ORDERS);

        assertThat(pacer.blockedUntilMs()).isEqualTo(2_000L);
        assertThat(releasedAt).containsExactly(0L, 2_000L);
    }

    @Test
    void anAbsurdSessionOrdersResetIsClampedToMaxBlockMs() throws Exception {
        var pacer = pacer(1_100L);

        send(pacer, HttpMethod.PATCH, SIM + ORDERS, 429,
                headers(SaxoOrderWritePacer.SESSION_ORDERS_RESET, "41233"));

        // Without the clamp a single malformed header would wedge every order write on this
        // connection for eleven hours, silently — the wedge bug FinnhubRateLimiter already had.
        assertThat(pacer.blockedUntilMs()).isEqualTo(3_000L);
    }

    @Test
    void appDayResetAloneIsIgnoredAndTheDefaultApplies() throws Exception {
        var pacer = pacer(1_100L);

        // X-RateLimit-AppDay-Reset runs to 86400 s and says nothing about THIS request's
        // cooldown — only the SessionOrders dimension carries the order limit.
        send(pacer, HttpMethod.PATCH, SIM + ORDERS, 429,
                headers("X-RateLimit-AppDay-Reset", "41233"));

        assertThat(pacer.blockedUntilMs()).isEqualTo(1_000L);
    }

    @Test
    void retryAfterIsUsedOnlyWhenTheSessionOrdersHeaderIsAbsent() throws Exception {
        var pacer = pacer(1_100L);

        send(pacer, HttpMethod.PATCH, SIM + ORDERS, 429, headers(HttpHeaders.RETRY_AFTER, "2"));

        assertThat(pacer.blockedUntilMs()).isEqualTo(2_000L);
    }

    @Test
    void theSessionOrdersHeaderWinsOverRetryAfter() throws Exception {
        var pacer = pacer(1_100L);
        var both = headers(SaxoOrderWritePacer.SESSION_ORDERS_RESET, "2");
        both.add(HttpHeaders.RETRY_AFTER, "1");

        send(pacer, HttpMethod.PATCH, SIM + ORDERS, 429, both);

        assertThat(pacer.blockedUntilMs()).isEqualTo(2_000L);
    }

    @Test
    void a429WithNoUsableHeaderFallsBackToTheConfiguredDefault() throws Exception {
        var pacer = pacer(1_100L);

        send(pacer, HttpMethod.PATCH, SIM + ORDERS, 429, new HttpHeaders());

        assertThat(pacer.blockedUntilMs()).isEqualTo(1_000L);
    }

    @Test
    void a409IsNotRateLimitFeedbackAndSetsNoBlock() throws Exception {
        var pacer = pacer(1_100L);

        // 409 is Saxo's answer to a byte-identical operation inside 15 s (patchLeg sends no
        // X-Request-ID). It says nothing about the order rate.
        send(pacer, HttpMethod.PATCH, SIM + ORDERS, 409, new HttpHeaders());

        assertThat(pacer.blockedUntilMs()).isZero();
    }

    // ---- header discovery: the pacer is the only place a Saxo response header is visible ----

    @Test
    void everyPacedResponseLogsItsRateLimitHeadersAndNothingElse() throws Exception {
        // Agora logs REQUEST headers only (ProviderCallLogger.emit), and the Saxo access token
        // lives in memory rather than on disk, so there is no way to curl the gateway and read a
        // 429's headers by hand. This line is how the header names are discovered after deploy —
        // on a 200 as much as on a 429, so the names show up before the first 429 ever arrives.
        var pacer = pacer(1_100L);
        var responseHeaders = new HttpHeaders();
        responseHeaders.add("X-RateLimit-SessionOrders-Remaining", "0");
        responseHeaders.add("X-RateLimit-AppDay-Reset", "41233");
        responseHeaders.add(HttpHeaders.CONTENT_TYPE, "application/json");

        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(SaxoOrderWritePacer.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        var previousLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
        try {
            send(pacer, HttpMethod.PATCH, SIM + ORDERS, 200, responseHeaders);
        } finally {
            logger.setLevel(previousLevel);
            logger.detachAppender(appender);
            appender.stop();
        }

        var headerLines = appender.list.stream()
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("saxo write pacer: response "))
                .toList();
        assertThat(headerLines).hasSize(1);
        assertThat(headerLines.get(0))
                .startsWith("saxo write pacer: response 200 PATCH /sim/openapi/trade/v2/orders headers {")
                .contains("X-RateLimit-SessionOrders-Remaining=0")
                .contains("X-RateLimit-AppDay-Reset=41233")
                // nothing but X-RateLimit-* may reach the log through this path
                .doesNotContain("Content-Type")
                .doesNotContain("application/json")
                .endsWith("}");
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws IOException;
    }

    /** Attaches a {@link ch.qos.logback.core.read.ListAppender} to this class's logger at INFO
     *  for the duration of {@code action}, then returns every captured formatted message. */
    private static List<String> captureLogLines(ThrowingAction action) throws IOException {
        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(SaxoOrderWritePacer.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        var previousLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
        try {
            action.run();
        } finally {
            logger.setLevel(previousLevel);
            logger.detachAppender(appender);
            appender.stop();
        }
        return appender.list.stream()
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    void aSecondPacedWriteThatHadToWaitLogsHowLongItWaited() throws Exception {
        // Without this line, "zero 429 tonight" is indistinguishable from "pacer inert": a wrong
        // path predicate or a kill switch left on would produce the same silence in the logs.
        var pacer = pacer(1_100L);

        var lines = captureLogLines(() -> {
            send(pacer, HttpMethod.PATCH, SIM + ORDERS);   // released at t=0, no wait
            clock.addAndGet(100L);
            send(pacer, HttpMethod.PATCH, SIM + ORDERS);   // must wait ~1000 ms
        });

        var waitedLines = lines.stream().filter(m -> m.startsWith("saxo write pacer: waited "))
                .toList();
        assertThat(waitedLines).hasSize(1);
        assertThat(waitedLines.get(0))
                .isEqualTo("saxo write pacer: waited 1000 ms for PATCH /sim/openapi/trade/v2/orders");
    }

    @Test
    void a429BlockLogsItsResolvedWaitAndWhichHeaderSourceItCameFrom() throws Exception {
        // Without this line a 429 that falls through to the configured default is invisible, so
        // the post-deploy header-name discovery cannot tell which branch prod actually exercised.
        var pacer = pacer(1_100L);

        var sessionOrdersLines = captureLogLines(() -> send(pacer, HttpMethod.PATCH, SIM + ORDERS,
                429, headers(SaxoOrderWritePacer.SESSION_ORDERS_RESET, "2")));
        assertThat(sessionOrdersLines)
                .contains("saxo write pacer: 429 blocks order writes for 2000 ms "
                        + "(header=session-orders-reset)");

        var pacer2 = pacer(1_100L);
        var defaultLines = captureLogLines(
                () -> send(pacer2, HttpMethod.PATCH, SIM + ORDERS, 429, new HttpHeaders()));
        assertThat(defaultLines)
                .contains("saxo write pacer: 429 blocks order writes for 1000 ms (header=default)");
    }

    // ---- (e) the kill switch ----

    @Test
    void aZeroMinIntervalIsAFullPassThroughIncludingAfterA429() throws Exception {
        var pacer = pacer(0L);

        send(pacer, HttpMethod.PATCH, SIM + ORDERS, 429,
                headers(SaxoOrderWritePacer.SESSION_ORDERS_RESET, "2"));
        send(pacer, HttpMethod.PATCH, SIM + ORDERS);

        assertThat(releasedAt).containsExactly(0L, 0L);
        assertThat(sleeps).isEmpty();
        assertThat(pacer.blockedUntilMs()).isZero();
    }

    // ---- (f) concurrency ----

    @Test
    void concurrentWritersAreSerializedAndNoneIsLostOrRefused() throws Exception {
        // Real clock and real sleeps here: an injected clock cannot express "five threads
        // arrive at once". 20 ms spacing keeps the whole test under ~100 ms.
        var pacer = new SaxoOrderWritePacer(20L, 3_000L, 1_000L, System::currentTimeMillis,
                Thread::sleep);
        var instants = Collections.synchronizedList(new ArrayList<Long>());
        ClientHttpRequestExecution execution = (req, body) -> {
            instants.add(System.currentTimeMillis());
            return new MockClientHttpResponse(new byte[0], 200);
        };

        ExecutorService pool = Executors.newFixedThreadPool(5);
        var start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    // The interceptor must NEVER throw: a throwing pacer turns a client-side
                    // queue into a broker failure.
                    assertThatCode(() -> pacer.intercept(
                            new MockClientHttpRequest(HttpMethod.PATCH, URI.create(SIM + ORDERS)),
                            new byte[0], execution)).doesNotThrowAnyException();
                    return null;
                }));
            }
            start.countDown();
            // get() re-throws anything a task swallowed into its Future — without this, a failed
            // assertion inside the pool would pass the test silently.
            for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(instants).hasSize(5);
        List<Long> sorted = new ArrayList<>(instants);
        Collections.sort(sorted);
        for (int i = 1; i < sorted.size(); i++) {
            assertThat(sorted.get(i) - sorted.get(i - 1)).isGreaterThanOrEqualTo(20L);
        }
    }

    // ---- (g) interrupts ----

    @Test
    void anInterruptDuringTheWaitRestoresTheFlagAndStillSendsTheRequest() throws Exception {
        var pacer = new SaxoOrderWritePacer(1_100L, 3_000L, 1_000L, clock::get,
                ms -> { throw new InterruptedException("interrupted while pacing"); });

        send(pacer, HttpMethod.PATCH, SIM + ORDERS);   // released at t=0, no wait
        clock.addAndGet(100L);
        send(pacer, HttpMethod.PATCH, SIM + ORDERS);   // must wait -> interrupted

        // An order write that is about to go out must never be skipped silently.
        assertThat(releasedAt).containsExactly(0L, 100L);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }
}
