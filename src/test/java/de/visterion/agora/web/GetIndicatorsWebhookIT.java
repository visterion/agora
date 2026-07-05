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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "agora.auth.tokens=test-token")
class GetIndicatorsWebhookIT {

    @LocalServerPort int port;

    @TestConfiguration
    static class StubProviderConfig {
        @Bean @Order(Ordered.HIGHEST_PRECEDENCE)
        MarketDataProvider indicatorsStubProvider() {
            return new MarketDataProvider() {
                public String name() { return "indicators-stub"; }
                public Quote quote(String s) { return new Quote(s, new BigDecimal("42.00"), BigDecimal.ZERO, "USD"); }
                public List<OhlcBar> ohlc(String s, int d) {
                    // 30 strictly rising closes → RSI = 100 (deterministic)
                    List<OhlcBar> bars = new ArrayList<>(30);
                    LocalDate base = LocalDate.of(2025, 1, 2);
                    for (int i = 0; i < 30; i++) {
                        BigDecimal c = new BigDecimal(100 + i);
                        bars.add(new OhlcBar(base.plusDays(i), c, c.add(BigDecimal.ONE),
                                c.subtract(BigDecimal.ONE), c, 1000L));
                    }
                    return bars;
                }
            };
        }
    }

    @Test
    void genericSpecOverWebhookWithBearer() {
        RestClient http = RestClient.create();
        ResponseEntity<String> resp = http.post()
                .uri("http://localhost:" + port + "/tools/get_indicators")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"symbol\":\"AAPL\",\"indicators\":[\"rsi\"]}")
                .retrieve().toEntity(String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody())
                .contains("\"symbol\":\"AAPL\"")
                .contains("\"label\":\"rsi\"")
                .contains("\"value\":100")
                .contains("\"available\":true");
    }

    @Test
    void rejectsWithoutBearer() {
        RestClient http = RestClient.create();
        try {
            http.post().uri("http://localhost:" + port + "/tools/get_indicators")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"symbol\":\"AAPL\",\"indicators\":[\"rsi\"]}")
                    .retrieve().toBodilessEntity();
            fail("expected 401");
        } catch (HttpClientErrorException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(401);
        }
    }
}
