package de.visterion.agora.trading;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class TradingHttpTest {
    static WireMockServer wm;
    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    @Test
    void responseSlowerThanTimeoutFailsFast() {
        wm.stubFor(get(urlEqualTo("/slow")).willReturn(okJson("{}").withFixedDelay(3_000)));
        RestClient client = RestClient.builder()
                .requestFactory(TradingHttp.requestFactory(250L))
                .baseUrl(wm.baseUrl())
                .build();
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> client.get().uri("/slow").retrieve().body(String.class))
                .isInstanceOf(ResourceAccessException.class);
        assertThat((System.nanoTime() - t0) / 1_000_000L).isLessThan(2_500L);
    }

    /**
     * M-T9: the default pool per route (5) is exhausted by 5 concurrently in-flight slow
     * calls; a 6th concurrent call must fail on POOL CHECKOUT within the 3s
     * connectionRequestTimeout — NOT hang for Apache's 3-minute default. We hold the pool
     * with a 6s response delay (well past the 3s checkout timeout) and fire 6 concurrent
     * requests: the 6th must complete (fail) well before the 6s response delay elapses,
     * proving it never got a connection and instead timed out waiting for one.
     */
    @Test
    void sixthConcurrentCallFailsOnPoolCheckoutNotResponse() throws Exception {
        wm.stubFor(get(urlEqualTo("/slow")).willReturn(okJson("{}").withFixedDelay(6_000)));
        RestClient client = RestClient.builder()
                .requestFactory(TradingHttp.requestFactory(10_000L))
                .baseUrl(wm.baseUrl())
                .build();

        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Long>> calls = IntStream.range(0, 6)
                    .<Callable<Long>>mapToObj(i -> () -> {
                        long t0 = System.nanoTime();
                        try {
                            client.get().uri("/slow").retrieve().body(String.class);
                        } catch (Exception ignored) {
                            // expected for at least one of the 6 (pool exhaustion or, for the
                            // 5 in-flight, the eventual slow response)
                        }
                        return (System.nanoTime() - t0) / 1_000_000L;
                    })
                    .toList();
            List<Future<Long>> futures = pool.invokeAll(calls);
            List<Long> durationsMs = futures.stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).toList();

            // At least one call must have failed fast on pool checkout (well under the 6s
            // response delay) — proving connectionRequestTimeout is wired to ~3s, not the
            // Apache default of 3 minutes.
            assertThat(durationsMs).anyMatch(ms -> ms < 5_000L);
        } finally {
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    /**
     * Spring runs request interceptors in registration order. The pacer MUST be registered
     * before ProviderCallLogger, otherwise its wait lands inside the logger's clock and every
     * paced call is logged with an inflated dur_ms — which is exactly the number the rollout
     * verification uses to compute send instants (line timestamp - dur_ms). This test proves
     * the ordering by its only externally visible consequence.
     */
    @Test
    void firstInterceptorRunsAheadOfTheCallLoggerSoItsWaitIsNotBilled() {
        wm.stubFor(get(urlEqualTo("/ping")).willReturn(okJson("{}")));
        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger("agora.providercall");
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        var previousLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
        try {
            RestClient client = TradingHttp.clientBuilder(10_000L, (request, body, execution) -> {
                try {
                    Thread.sleep(400L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return execution.execute(request, body);
            }).baseUrl(wm.baseUrl()).build();

            long t0 = System.nanoTime();
            client.get().uri("/ping").retrieve().body(String.class);
            long totalMs = (System.nanoTime() - t0) / 1_000_000L;

            assertThat(totalMs).isGreaterThanOrEqualTo(400L);
            String line = appender.list.stream()
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.startsWith("provider_call"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no provider_call line was logged"));
            long durMs = Long.parseLong(line.replaceAll(".*\\bdur_ms=(\\d+).*", "$1"));
            assertThat(durMs).isLessThan(400L);
        } finally {
            logger.setLevel(previousLevel);
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
