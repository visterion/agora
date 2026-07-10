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
    void storeKeyFollowsConnectionIdConvention() {
        assertThat(SaxoBrokerProviderFactory.storeKey(cfg(ConnectionConfig.Environment.PAPER)))
                .isEqualTo("saxo-sim");
        assertThat(SaxoBrokerProviderFactory.storeKey(cfg(ConnectionConfig.Environment.LIVE)))
                .isEqualTo("saxo-live");
    }

    @Test
    void createBuildsSaxoProvider() {
        var p = new SaxoBrokerProviderFactory(new SaxoTokenStores(dir, () -> 0L), 10_000L)
                .create(cfg(ConnectionConfig.Environment.PAPER));
        assertThat(p).isInstanceOf(SaxoBrokerProvider.class);
        assertThat(p.name()).isEqualTo("saxo");
    }
}
