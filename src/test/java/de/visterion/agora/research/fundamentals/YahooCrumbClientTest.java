package de.visterion.agora.research.fundamentals;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.agora.data.MarketDataException;
import org.junit.jupiter.api.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class YahooCrumbClientTest {
    static WireMockServer wm;
    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    private YahooCrumbClient client() {
        // Route every endpoint — including the cookie-bootstrap hosts (real fc.yahoo.com /
        // finance.yahoo.com in production) — at the same WireMock instance.
        return new YahooCrumbClient("TestAgent/1.0", wm.baseUrl(), wm.baseUrl(), wm.baseUrl(), wm.baseUrl());
    }

    private void stubBootstrap() {
        wm.stubFor(get(urlPathEqualTo("/")).willReturn(aResponse().withStatus(204)));
        wm.stubFor(get(urlPathEqualTo("/quote/AAPL")).willReturn(aResponse().withStatus(200)));
    }

    @Test
    void singleFlightCrumbHandshake() throws Exception {
        stubBootstrap();
        wm.stubFor(get(urlPathEqualTo("/v1/test/getcrumb"))
                .willReturn(aResponse().withStatus(200).withBody("tok12345").withFixedDelay(150)));

        YahooCrumbClient c = client();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Runnable task = () -> {
            ready.countDown();
            try { go.await(); } catch (InterruptedException ignored) {}
            c.crumb();
        };
        Thread t1 = new Thread(task);
        Thread t2 = new Thread(task);
        t1.start(); t2.start();
        ready.await();
        go.countDown();
        t1.join(5000);
        t2.join(5000);

        wm.verify(1, getRequestedFor(urlEqualTo("/v1/test/getcrumb")));
    }

    @Test
    void rateLimitedCrumbThrowsUncached() {
        stubBootstrap();
        wm.stubFor(get(urlPathEqualTo("/v1/test/getcrumb"))
                .willReturn(aResponse().withStatus(200).withBody("Too Many Requests")));

        YahooCrumbClient c = client();
        assertThatThrownBy(c::crumb).isInstanceOf(MarketDataException.class);
        // not cached: a second call re-requests getcrumb
        assertThatThrownBy(c::crumb).isInstanceOf(MarketDataException.class);
        wm.verify(2, getRequestedFor(urlEqualTo("/v1/test/getcrumb")));
    }

    @Test
    void timeseriesRateLimitedThrowsInsteadOfReturningErrorEnvelope() throws Exception {
        stubBootstrap();
        wm.stubFor(get(urlPathEqualTo("/v1/test/getcrumb"))
                .willReturn(aResponse().withStatus(200).withBody("tok12345")));
        // Yahoo's real 429 response is a JSON error envelope that would otherwise parse
        // cleanly as "zero series" if not mapped to a thrown exception (see FIX 1).
        wm.stubFor(get(urlPathMatching("/ws/fundamentals-timeseries/.*"))
                .willReturn(aResponse().withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"finance\":{\"error\":{\"code\":\"Too Many Requests\"}}}")));

        YahooCrumbClient c = client();
        // Never silently swallowed into a parseable empty envelope: every call throws.
        assertThatThrownBy(() -> c.timeseries("SAP.DE", "annualTotalAssets"))
                .isInstanceOf(MarketDataException.class);
        assertThatThrownBy(() -> c.timeseries("SAP.DE", "annualTotalAssets"))
                .isInstanceOf(MarketDataException.class);
    }

    @Test
    void timeseries5xxThrowsInsteadOfReturningErrorEnvelope() throws Exception {
        stubBootstrap();
        wm.stubFor(get(urlPathEqualTo("/v1/test/getcrumb"))
                .willReturn(aResponse().withStatus(200).withBody("tok12345")));
        wm.stubFor(get(urlPathMatching("/ws/fundamentals-timeseries/.*"))
                .willReturn(aResponse().withStatus(503)));

        YahooCrumbClient c = client();
        assertThatThrownBy(() -> c.timeseries("SAP.DE", "annualTotalAssets"))
                .isInstanceOf(MarketDataException.class);
    }

    @Test
    void invalidCrumbTriggersOneReHandshake() throws Exception {
        stubBootstrap();
        wm.stubFor(get(urlPathEqualTo("/v1/test/getcrumb"))
                .willReturn(aResponse().withStatus(200).withBody("tok12345")));
        wm.stubFor(get(urlPathMatching("/ws/fundamentals-timeseries/.*"))
                .inScenario("crumb-retry")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(401))
                .willSetStateTo("retried"));
        wm.stubFor(get(urlPathMatching("/ws/fundamentals-timeseries/.*"))
                .inScenario("crumb-retry")
                .whenScenarioStateIs("retried")
                .willReturn(okJson("{\"timeseries\":{\"result\":[]}}")));

        YahooCrumbClient c = client();
        var node = c.timeseries("SAP.DE", "annualTotalAssets");
        assertThat(node.path("timeseries").path("result").isArray()).isTrue();
        wm.verify(2, getRequestedFor(urlEqualTo("/v1/test/getcrumb")));
    }
}
