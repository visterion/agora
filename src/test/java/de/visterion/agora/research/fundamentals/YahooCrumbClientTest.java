package de.visterion.agora.research.fundamentals;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.observability.ProviderCallLogger;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;

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

    @Test
    void malformedUrlSurfacesMarketDataExceptionNotIllegalArgument() {
        // Regression for the finally-block bug: `get(url)` used to re-parse `url` a second
        // time inside the finally (as an arg to ProviderCallLogger.record), OUTSIDE the
        // try/catch that wraps the URI.create() used for the actual request. A malformed
        // base url (e.g. a misconfigured query1) would make URI.create() throw once inside
        // the try — correctly wrapped into a MarketDataException — but then the finally's
        // re-parse would throw IllegalArgumentException again, and per Java finally
        // semantics that REPLACES the in-flight MarketDataException with the raw
        // IllegalArgumentException. Point query1 at a syntactically invalid URI while
        // keeping the cookie-bootstrap hosts (fcBaseUrl/financeBaseUrl) valid, so the
        // crumb handshake's softGet(...) calls succeed harmlessly and only the final
        // get(query1 + "/v1/test/getcrumb") call hits the malformed url.
        stubBootstrap();
        YahooCrumbClient c = new YahooCrumbClient("TestAgent/1.0", "ht!tp://bad", wm.baseUrl(), wm.baseUrl(), wm.baseUrl());

        assertThatThrownBy(c::crumb)
                .isInstanceOf(MarketDataException.class);
    }

    @Test
    void getRawTransportFailureThrowsMarketDataExceptionWithCauseAndLogsOnce() throws Exception {
        // Point at a port nothing is listening on (connection refused) to force a
        // transport-level failure (not an HTTP status) inside getRaw.
        int deadPort = findUnusedPort();
        ProviderCallLogger.configure(true, 4096);
        Logger l = (Logger) org.slf4j.LoggerFactory.getLogger("agora.providercall");
        ListAppender<ILoggingEvent> app = new ListAppender<>();
        app.start();
        l.addAppender(app);
        try {
            YahooCrumbClient c = client();
            String url = "http://localhost:" + deadPort + "/v1/test/getcrumb";
            assertThatThrownBy(() -> c.getRaw(url))
                    .isInstanceOf(MarketDataException.class)
                    .satisfies(ex -> assertThat(ex.getCause()).isNotNull());

            var lines = app.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.contains("/v1/test/getcrumb"))
                    .toList();
            assertThat(lines).hasSize(1);
        } finally {
            l.detachAppender(app);
        }
    }

    private static int findUnusedPort() throws Exception {
        try (var s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    void logsProviderCallWithCrumbRedacted() throws Exception {
        stubBootstrap();
        wm.stubFor(get(urlPathEqualTo("/v1/test/getcrumb"))
                .willReturn(aResponse().withStatus(200).withBody("REALCRUMB123")));
        wm.stubFor(get(urlPathMatching("/ws/fundamentals-timeseries/.*"))
                .willReturn(okJson("{\"timeseries\":{\"result\":[]}}")));

        ProviderCallLogger.configure(true, 4096);
        Logger l = (Logger) org.slf4j.LoggerFactory.getLogger("agora.providercall");
        ListAppender<ILoggingEvent> app = new ListAppender<>();
        app.start();
        l.addAppender(app);
        try {
            YahooCrumbClient c = client();
            c.timeseries("AAPL", "annualEbit");
            // NOTE: WireMock always serves as host "localhost", so provider=yahoo (which is
            // derived from the real hostname in production) cannot be asserted against this
            // harness — filter on the timeseries path instead and verify redaction, which is
            // the behavior under test here.
            var timeseriesLines = app.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.contains("/ws/fundamentals-timeseries/"))
                    .toList();
            assertThat(timeseriesLines).isNotEmpty();
            assertThat(timeseriesLines).allSatisfy(m -> assertThat(m)
                    .startsWith("provider_call")
                    .contains("method=GET")
                    .contains("status=200")
                    .contains("crumb=***")
                    .doesNotContain("crumb=REALCRUMB123"));
        } finally {
            l.detachAppender(app);
        }
    }

    @Test
    void quoteSummary_200_returnsParsedNode() throws Exception {
        stubBootstrap();
        wm.stubFor(get(urlPathEqualTo("/v1/test/getcrumb"))
                .willReturn(aResponse().withStatus(200).withBody("tok12345")));
        wm.stubFor(get(urlPathMatching("/v10/finance/quoteSummary/.*"))
                .willReturn(okJson("{\"quoteSummary\":{\"result\":[{\"assetProfile\":{\"sector\":\"Technology\"}}],\"error\":null}}")));

        YahooCrumbClient c = client();
        JsonNode node = c.quoteSummary("SAP.DE", "assetProfile,recommendationTrend");

        assertThat(node.path("quoteSummary").path("result").isArray()).isTrue();
        assertThat(node.path("quoteSummary").path("result").isEmpty()).isFalse();
    }

    @Test
    void quoteSummary_404JsonEnvelope_returnsParsedNode_noThrow() throws Exception {
        stubBootstrap();
        wm.stubFor(get(urlPathEqualTo("/v1/test/getcrumb"))
                .willReturn(aResponse().withStatus(200).withBody("tok12345")));
        wm.stubFor(get(urlPathMatching("/v10/finance/quoteSummary/.*"))
                .willReturn(aResponse().withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"quoteSummary\":{\"result\":null,\"error\":{\"code\":\"Not Found\"}}}")));

        YahooCrumbClient c = client();
        JsonNode node = c.quoteSummary("NOSUCH.DE", "assetProfile");

        assertThat(node.path("quoteSummary").path("result").isNull()).isTrue();
    }

    @Test
    void quoteSummary_401_rehandshakesWithFreshCrumbThenSucceeds() throws Exception {
        stubBootstrap();
        wm.stubFor(get(urlPathEqualTo("/v1/test/getcrumb"))
                .inScenario("qs-crumb-retry")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(200).withBody("staleCrumb"))
                .willSetStateTo("rehandshaked"));
        wm.stubFor(get(urlPathEqualTo("/v1/test/getcrumb"))
                .inScenario("qs-crumb-retry")
                .whenScenarioStateIs("rehandshaked")
                .willReturn(aResponse().withStatus(200).withBody("freshCrumb")));

        wm.stubFor(get(urlPathMatching("/v10/finance/quoteSummary/.*"))
                .withQueryParam("crumb", equalTo("staleCrumb"))
                .willReturn(aResponse().withStatus(401)));
        wm.stubFor(get(urlPathMatching("/v10/finance/quoteSummary/.*"))
                .withQueryParam("crumb", equalTo("freshCrumb"))
                .willReturn(okJson("{\"quoteSummary\":{\"result\":[{\"assetProfile\":{}}],\"error\":null}}")));

        YahooCrumbClient c = client();
        JsonNode node = c.quoteSummary("SAP.DE", "assetProfile");

        assertThat(node.path("quoteSummary").path("result").isArray()).isTrue();
        wm.verify(2, getRequestedFor(urlEqualTo("/v1/test/getcrumb")));
        wm.verify(1, getRequestedFor(urlPathMatching("/v10/finance/quoteSummary/.*"))
                .withQueryParam("crumb", equalTo("freshCrumb")));
    }

    @Test
    void quoteSummary_429_throwsWithoutRehandshake() throws Exception {
        stubBootstrap();
        wm.stubFor(get(urlPathEqualTo("/v1/test/getcrumb"))
                .willReturn(aResponse().withStatus(200).withBody("tok12345")));
        wm.stubFor(get(urlPathMatching("/v10/finance/quoteSummary/.*"))
                .willReturn(aResponse().withStatus(429)));

        YahooCrumbClient c = client();
        assertThatThrownBy(() -> c.quoteSummary("SAP.DE", "assetProfile"))
                .isInstanceOf(MarketDataException.class);

        // initial crumb handshake = 1 getcrumb call; no re-handshake triggered by 429.
        wm.verify(1, getRequestedFor(urlEqualTo("/v1/test/getcrumb")));
        wm.verify(1, getRequestedFor(urlPathMatching("/v10/finance/quoteSummary/.*")));
    }

    @Test
    void quoteSummary_htmlBody_throws() throws Exception {
        stubBootstrap();
        wm.stubFor(get(urlPathEqualTo("/v1/test/getcrumb"))
                .willReturn(aResponse().withStatus(200).withBody("tok12345")));
        wm.stubFor(get(urlPathMatching("/v10/finance/quoteSummary/.*"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("<html><body>not json</body></html>")));

        YahooCrumbClient c = client();
        assertThatThrownBy(() -> c.quoteSummary("SAP.DE", "assetProfile"))
                .isInstanceOf(MarketDataException.class);
    }
}
