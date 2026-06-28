package de.visterion.agora.web;

import de.visterion.agora.data.MarketDataProvider;
import de.visterion.agora.data.OhlcBar;
import de.visterion.agora.data.Quote;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration test: proves that get_atr is reachable over the Slice-1 webhook
 * front-door (POST /tools/{name}) with bearer-token auth, using a deterministic
 * stub provider (no real network calls).
 *
 * <p>Fixture: 30 rising bars where close_i = 100+i, high_i = close_i+1, low_i = close_i-1.
 * True Range for each interior bar = max(2, 2, 0) = 2.
 * ATR = SMA of last 22 TRs = 2.0 exactly — a real Yahoo call could never produce this.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "agora.auth.tokens=test-token")
class GetAtrWebhookIT {

    @LocalServerPort
    int port;

    /**
     * Stub MarketDataProvider annotated @Order(HIGHEST_PRECEDENCE) so it is first
     * in the List&lt;MarketDataProvider&gt; injected into MarketDataService.
     *
     * <p>NOTE: @Order (not @Primary alone) controls List&lt;T&gt; injection order in Spring.
     * @Primary alone does not guarantee the stub heads the fallback chain — @Order does.</p>
     */
    @TestConfiguration
    static class StubProviderConfig {

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        MarketDataProvider atrStubMarketDataProvider() {
            return new MarketDataProvider() {
                @Override
                public String name() { return "atr-stub"; }

                @Override
                public Quote quote(String symbol) {
                    return new Quote(symbol, new BigDecimal("42.00"), BigDecimal.ZERO, "USD");
                }

                /**
                 * 30 rising bars: close=100+i, high=close+1, low=close-1.
                 * TR for each interior bar = 2 → ATR(22) = 2.0.
                 */
                @Override
                public List<OhlcBar> ohlc(String symbol, int days) {
                    List<OhlcBar> bars = new ArrayList<>(30);
                    LocalDate base = LocalDate.of(2025, 1, 2);
                    for (int i = 0; i < 30; i++) {
                        BigDecimal close = new BigDecimal(100 + i);
                        BigDecimal high  = close.add(BigDecimal.ONE);
                        BigDecimal low   = close.subtract(BigDecimal.ONE);
                        bars.add(new OhlcBar(base.plusDays(i), close, high, low, close, 1_000L));
                    }
                    return bars;
                }
            };
        }
    }

    @Test
    void getAtrOverWebhookWithBearer() {
        RestClient http = RestClient.create();
        ResponseEntity<String> resp = http.post()
                .uri("http://localhost:" + port + "/tools/get_atr")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"symbol\":\"AAPL\"}")
                .retrieve()
                .toEntity(String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        String body = resp.getBody();
        assertThat(body)
                // Determinism proof — a real Yahoo call could not produce these values
                .contains("\"symbol\":\"AAPL\"")
                .contains("\"available\":true")
                // ATR=2.0 from the fixture (SMA of 22 TRs of exactly 2 each)
                .contains("\"atr\":2");
    }

    @Test
    void rejectsWithoutBearer() {
        RestClient http = RestClient.create();
        try {
            http.post()
                    .uri("http://localhost:" + port + "/tools/get_atr")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"symbol\":\"AAPL\"}")
                    .retrieve()
                    .toBodilessEntity();
            fail("expected 401");
        } catch (HttpClientErrorException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(401);
        }
    }
}
