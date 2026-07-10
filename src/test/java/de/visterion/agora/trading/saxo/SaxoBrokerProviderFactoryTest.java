package de.visterion.agora.trading.saxo;

import de.visterion.agora.trading.ConnectionConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SaxoBrokerProviderFactoryTest {

    @TempDir Path dir;

    private static ConnectionConfig cfg(ConnectionConfig.Environment env) {
        ConnectionConfig c = new ConnectionConfig();
        c.setProvider("saxo");
        c.setEnvironment(env);
        c.setBaseUrl("http://localhost:1");
        c.setKeyId("k"); c.setSecret("s");
        return c;
    }

    @Test
    void providerKeyIsSaxo() {
        assertThat(new SaxoBrokerProviderFactory(new SaxoTokenStores(dir, () -> 0L), 10_000L).provider())
                .isEqualTo("saxo");
    }

    @Test
    void createBuildsSaxoProvider() {
        var p = new SaxoBrokerProviderFactory(new SaxoTokenStores(dir, () -> 0L), 10_000L)
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
        var factory = new SaxoBrokerProviderFactory(stores, 10_000L);

        var provider = (SaxoBrokerProvider) factory.create("saxo-custom", cfg(ConnectionConfig.Environment.PAPER));

        stores.forConnection("saxo-custom").update("acc-1", 1200, "ref-1");
        assertThat(provider.tokenStore().validAccessToken()).contains("acc-1");
    }
}
