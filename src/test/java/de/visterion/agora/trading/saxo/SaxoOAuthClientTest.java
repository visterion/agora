package de.visterion.agora.trading.saxo;

import com.github.tomakehurst.wiremock.WireMockServer;
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
}
