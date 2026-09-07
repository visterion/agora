package de.visterion.agora.trading.saxo;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.agora.trading.ConnectionConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

class SaxoBrokerProviderFactoryTest {

    @TempDir Path dir;

    static WireMockServer wm;

    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    /** Production defaults from application.yaml: 1100 / 3000 / 1000. */
    private static SaxoBrokerProviderFactory factory(SaxoTokenStores stores) {
        return new SaxoBrokerProviderFactory(stores, 10_000L, 1_100L, 3_000L, 1_000L);
    }

    private static ConnectionConfig cfg(ConnectionConfig.Environment env) {
        return cfg(env, "http://localhost:1");
    }

    private static ConnectionConfig cfg(ConnectionConfig.Environment env, String baseUrl) {
        ConnectionConfig c = new ConnectionConfig();
        c.setProvider("saxo");
        c.setEnvironment(env);
        c.setBaseUrl(baseUrl);
        c.setKeyId("k"); c.setSecret("s");
        return c;
    }

    @Test
    void providerKeyIsSaxo() {
        assertThat(factory(new SaxoTokenStores(dir, () -> 0L)).provider()).isEqualTo("saxo");
    }

    @Test
    void createBuildsSaxoProvider() {
        var p = factory(new SaxoTokenStores(dir, () -> 0L))
                .create("saxo-sim", cfg(ConnectionConfig.Environment.PAPER));
        assertThat(p).isInstanceOf(SaxoBrokerProvider.class);
        assertThat(p.name()).isEqualTo("saxo");
    }

    // M-T7: the token store must be keyed by connection id, not by environment — any saxo
    // slot not literally named "saxo-sim"/"saxo-live" must still get the store the auth
    // endpoints/refresher/data provider use for that same connection id.
    @Test
    void createKeysTheTokenStoreByConnectionIdNotEnvironment() {
        SaxoTokenStores stores = new SaxoTokenStores(dir, () -> 0L);
        var factory = factory(stores);

        var provider = (SaxoBrokerProvider) factory.create("saxo-custom", cfg(ConnectionConfig.Environment.PAPER));

        stores.forConnection("saxo-custom").update("acc-1", 1200, "ref-1");
        assertThat(provider.tokenStore().validAccessToken()).contains("acc-1");
    }

    /**
     * The pacer only helps if it is actually installed on the RestClient the provider writes
     * through — the failure mode this test exists for is a factory that builds the pacer and
     * then forgets to pass it to clientBuilder. Two DELETEs on /trade/v2/orders must therefore
     * be at least min-interval apart. 400 ms instead of the production 1100 keeps the test fast.
     */
    @Test
    void theOrderWritePacerIsInstalledOnTheProvidersClient() {
        wm.stubFor(get(urlEqualTo("/port/v1/accounts/me")).willReturn(okJson("""
            {"Data":[{"AccountKey":"Acc+Key/1==","ClientKey":"Cli+Key/1==","AccountId":"123"}]}
            """)));
        wm.stubFor(delete(urlPathEqualTo("/trade/v2/orders/O-1")).willReturn(okJson("{}")));
        SaxoTokenStores stores = new SaxoTokenStores(dir, () -> 0L);
        stores.forConnection("saxo-sim").update("acc-token", 1200, "ref");
        var factory = new SaxoBrokerProviderFactory(stores, 10_000L, 400L, 3_000L, 1_000L);

        var provider = factory.create("saxo-sim",
                cfg(ConnectionConfig.Environment.PAPER, wm.baseUrl()));

        long t0 = System.nanoTime();
        provider.cancel("O-1");
        provider.cancel("O-1");
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        assertThat(elapsedMs).isGreaterThanOrEqualTo(400L);
        wm.verify(2, deleteRequestedFor(urlPathEqualTo("/trade/v2/orders/O-1")));
    }
}
