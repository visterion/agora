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
}
