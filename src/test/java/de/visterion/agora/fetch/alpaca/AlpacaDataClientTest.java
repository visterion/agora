package de.visterion.agora.fetch.alpaca;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.*;

class AlpacaDataClientTest {
    @Test void configured_trueWhenBothCredsPresent() {
        var c = new AlpacaDataClient("https://data.alpaca.markets", "PKID", "secret");
        assertThat(c.configured()).isTrue();
        assertThat(c.http()).isNotNull();
    }

    @Test void configured_falseWhenBlank() {
        assertThat(new AlpacaDataClient("https://data.alpaca.markets", "", "secret").configured()).isFalse();
        assertThat(new AlpacaDataClient("https://data.alpaca.markets", "PKID", "").configured()).isFalse();
    }

    @Test void testCtor_takesRestClient() {
        var c = new AlpacaDataClient(RestClient.builder().baseUrl("http://x").build(), true);
        assertThat(c.configured()).isTrue();
    }
}
