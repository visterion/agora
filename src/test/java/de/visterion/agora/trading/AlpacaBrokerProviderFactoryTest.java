package de.visterion.agora.trading;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AlpacaBrokerProviderFactoryTest {

    @Test
    void providerKeyIsAlpaca() {
        assertThat(new AlpacaBrokerProviderFactory().provider()).isEqualTo("alpaca");
    }

    @Test
    void createBuildsProviderFromConfig() {
        ConnectionConfig cfg = new ConnectionConfig();
        cfg.setProvider("alpaca");
        cfg.setEnvironment(ConnectionConfig.Environment.PAPER);
        cfg.setBaseUrl("http://localhost:1");
        cfg.setKeyId("k");
        cfg.setSecret("s");

        BrokerProvider p = new AlpacaBrokerProviderFactory().create(cfg);

        assertThat(p).isInstanceOf(AlpacaBrokerProvider.class);
        assertThat(p.name()).isEqualTo("alpaca");
    }
}
