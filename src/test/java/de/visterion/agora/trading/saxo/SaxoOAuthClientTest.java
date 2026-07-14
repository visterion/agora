package de.visterion.agora.trading.saxo;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.agora.observability.ProviderCallLogger;
import de.visterion.agora.trading.ConnectionConfig;
import org.junit.jupiter.api.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class SaxoOAuthClientTest {

    static WireMockServer wm;
    SaxoOAuthClient client = new SaxoOAuthClient();
    ConnectionConfig cfg;

    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }

    @BeforeEach
    void setUp() {
        wm.resetAll();
        cfg = new ConnectionConfig();
        cfg.setProvider("saxo");
        cfg.setEnvironment(ConnectionConfig.Environment.PAPER);
        cfg.setKeyId("app-key");
        cfg.setSecret("app-secret");
        cfg.getExtra().put("auth-base-url", wm.baseUrl());
    }

    @Test
    void exchangeCodeSendsFormAndBasicAuthAndParsesTokens() {
        wm.stubFor(post(urlEqualTo("/token")).willReturn(okJson("""
            {"access_token":"acc-1","token_type":"Bearer","expires_in":1200,
             "refresh_token":"ref-1","refresh_token_expires_in":2400}
            """)));

        var t = client.exchangeCode(cfg, "the-code");

        assertThat(t.accessToken()).isEqualTo("acc-1");
        assertThat(t.expiresInSeconds()).isEqualTo(1200);
        assertThat(t.refreshToken()).isEqualTo("ref-1");
        wm.verify(postRequestedFor(urlEqualTo("/token"))
                .withBasicAuth(new com.github.tomakehurst.wiremock.client.BasicCredentials("app-key", "app-secret"))
                .withRequestBody(containing("grant_type=authorization_code"))
                .withRequestBody(containing("code=the-code")));
    }

    @Test
    void exchangeCodeOmitsRedirectUriWhenBlank() {
        wm.stubFor(post(urlEqualTo("/token")).willReturn(okJson("""
            {"access_token":"acc-1","expires_in":1200,"refresh_token":"ref-1"}
            """)));
        cfg.getExtra().put("redirect-uri", "   ");

        client.exchangeCode(cfg, "the-code");

        wm.verify(postRequestedFor(urlEqualTo("/token"))
                .withRequestBody(notContaining("redirect_uri")));
    }

    @Test
    void refreshSendsRefreshGrant() {
        wm.stubFor(post(urlEqualTo("/token")).willReturn(okJson("""
            {"access_token":"acc-2","expires_in":1200,"refresh_token":"ref-2"}
            """)));

        var t = client.refresh(cfg, "ref-1");

        assertThat(t.refreshToken()).isEqualTo("ref-2");
        wm.verify(postRequestedFor(urlEqualTo("/token"))
                .withRequestBody(containing("grant_type=refresh_token"))
                .withRequestBody(containing("refresh_token=ref-1")));
    }

    @Test
    void http400MeansInvalidGrant() {
        wm.stubFor(post(urlEqualTo("/token")).willReturn(aResponse().withStatus(400)));
        assertThatThrownBy(() -> client.refresh(cfg, "ref-dead"))
                .isInstanceOf(SaxoOAuthClient.InvalidGrantException.class);
    }

    @Test
    void http401MeansAppCredentialsRejectedNotSessionDeath() {
        wm.stubFor(post(urlEqualTo("/token")).willReturn(aResponse().withStatus(401)));
        assertThatThrownBy(() -> client.refresh(cfg, "ref-1"))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(SaxoOAuthClient.InvalidGrantException.class)
                .hasMessageContaining("saxo app credentials rejected (HTTP 401)");
    }

    @Test
    void http500IsTransient() {
        wm.stubFor(post(urlEqualTo("/token")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> client.refresh(cfg, "ref-1"))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(SaxoOAuthClient.InvalidGrantException.class);
    }

    @Test
    void authBaseUrlDerivedFromEnvironment() {
        ConnectionConfig sim = new ConnectionConfig();
        sim.setEnvironment(ConnectionConfig.Environment.PAPER);
        ConnectionConfig live = new ConnectionConfig();
        live.setEnvironment(ConnectionConfig.Environment.LIVE);
        assertThat(SaxoOAuthClient.authBaseUrl(sim)).isEqualTo("https://sim.logonvalidation.net");
        assertThat(SaxoOAuthClient.authBaseUrl(live)).isEqualTo("https://live.logonvalidation.net");
        assertThat(SaxoOAuthClient.authBaseUrl(cfg)).isEqualTo(wm.baseUrl());   // extra overrides
    }

    @Test
    void slowTokenEndpointFailsFast() {
        wm.stubFor(post(urlEqualTo("/token"))
                .willReturn(okJson("{\"access_token\":\"a\",\"expires_in\":1200,\"refresh_token\":\"r\"}")
                        .withFixedDelay(3_000)));
        var fastClient = new SaxoOAuthClient(250L);
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> fastClient.refresh(cfg, "ref-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unreachable");
        assertThat((System.nanoTime() - t0) / 1_000_000L).isLessThan(2_500L);
    }

    @Test
    void logsProviderCallWithRefreshTokenAndBasicAuthRedacted() {
        wm.stubFor(post(urlEqualTo("/token")).willReturn(okJson(
                "{\"access_token\":\"a\",\"expires_in\":1200,\"refresh_token\":\"NEWSEKRET\"}")));

        ProviderCallLogger.configure(true, 4096);
        Logger l = (Logger) org.slf4j.LoggerFactory.getLogger("agora.providercall");
        ListAppender<ILoggingEvent> app = new ListAppender<>();
        app.start();
        l.addAppender(app);
        try {
            client.refresh(cfg, "OLDSEKRET");
            // NOTE: WireMock always serves as host "localhost", so provider=saxo (which is
            // derived from the real hostname in production, e.g. sim.logonvalidation.net)
            // cannot be asserted against this harness — filter on the /token path instead
            // and verify redaction, which is the behavior under test here.
            var tokenLines = app.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.contains("path=/token"))
                    .toList();
            assertThat(tokenLines).isNotEmpty();
            assertThat(tokenLines).allSatisfy(m -> assertThat(m)
                    .startsWith("provider_call")
                    .contains("method=POST")
                    .contains("status=200")
                    .doesNotContain("OLDSEKRET")
                    .doesNotContain("NEWSEKRET")
                    .contains("refresh_token=***")
                    .contains("Authorization=***"));
        } finally {
            l.detachAppender(app);
        }
    }
}
