package de.visterion.agora.web;

import de.visterion.agora.data.MarketDataProvider;
import de.visterion.agora.data.OhlcBar;
import de.visterion.agora.data.Quote;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration test: proves that get_quote is reachable over the Slice-1 webhook
 * front-door (POST /tools/{name}) with bearer-token auth, using a stub provider
 * (no real network calls).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "agora.auth.tokens=test-token")
class GetQuoteWebhookIT {

    @LocalServerPort
    int port;

    /**
     * Stub MarketDataProvider — @Primary + @Order(HIGHEST_PRECEDENCE) so it is always
     * first in the List&lt;MarketDataProvider&gt; injected into MarketDataService.
     * {@code @Primary} alone does not control List&lt;T&gt; injection order in Spring;
     * {@code @Order} is required to guarantee the stub heads the fallback chain.
     * Deterministic, no real network calls.
     */
    @TestConfiguration
    static class StubProviderConfig {

        @Bean
        @Primary
        @Order(Integer.MIN_VALUE)
        MarketDataProvider stubMarketDataProvider() {
            return new MarketDataProvider() {
                @Override
                public String name() { return "stub"; }

                @Override
                public Quote quote(String symbol) {
                    return new Quote(symbol, new BigDecimal("201.34"), new BigDecimal("0.83"), "USD");
                }

                @Override
                public List<OhlcBar> ohlc(String symbol, int days) {
                    return List.of();
                }
            };
        }
    }

    @Test
    void getQuoteOverWebhookWithBearer() {
        RestClient http = RestClient.create();
        ResponseEntity<String> resp = http.post()
                .uri("http://localhost:" + port + "/tools/get_quote")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"symbols\":[\"AAPL\"]}")
                .retrieve()
                .toEntity(String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody())
                .contains("\"symbol\":\"AAPL\"")
                .contains("\"quotes\"")
                // 201.34 is the stub's sentinel price — a real Yahoo call would return a
                // different market price, so this assertion proves the stub answered.
                .contains("201.34");
    }

    @Test
    void rejectsWithoutBearer() {
        RestClient http = RestClient.create();
        try {
            http.post()
                    .uri("http://localhost:" + port + "/tools/get_quote")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{}")
                    .retrieve()
                    .toBodilessEntity();
            fail("expected 401");
        } catch (HttpClientErrorException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(401);
        }
    }
}
