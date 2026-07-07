package de.visterion.agora.web;

import de.visterion.agora.trading.Account;
import de.visterion.agora.trading.BracketOrderRequest;
import de.visterion.agora.trading.BrokerProvider;
import de.visterion.agora.trading.BrokerProviderFactory;
import de.visterion.agora.trading.OrderResult;
import de.visterion.agora.trading.Position;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration test: proves that place_bracket is reachable over the webhook front-door
 * (POST /tools/place_bracket) with trading-token auth, and is correctly blocked when
 * a general-only token or no token is presented.
 *
 * <p>A stub BrokerProvider returns a deterministic {@code OrderResult.accepted("oid-1", ...)}.
 * The sentinel "oid-1" proves the stub answered — a real Alpaca call could never produce
 * exactly this value.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "agora.auth.tokens=gen-token",
                "agora.trading.tokens=trade-token",
                "agora.trading.connections.test-conn.provider=stub",
                "agora.trading.connections.test-conn.environment=paper",
                "agora.trading.connections.test-conn.key-id=k",
                "agora.trading.connections.test-conn.secret=s"
        })
class PlaceBracketWebhookIT {

    @LocalServerPort
    int port;

    /**
     * Stub BrokerProviderFactory registered under provider key "stub" so ConnectionRegistry
     * resolves the "test-conn" connection to this stub instead of a real Alpaca provider.
     */
    @TestConfiguration
    static class StubBrokerConfig {

        @Bean
        BrokerProviderFactory stubBrokerProviderFactory() {
            return new BrokerProviderFactory() {
                @Override public String provider() { return "stub"; }

                @Override
                public BrokerProvider create(de.visterion.agora.trading.ConnectionConfig cfg) {
                    return new BrokerProvider() {
                        @Override public String name() { return "stub"; }

                        @Override
                        public OrderResult submitBracket(BracketOrderRequest req) {
                            return OrderResult.accepted("oid-1", req.clientRef(), "accepted");
                        }

                        @Override
                        public OrderResult modifyBracket(String brokerOrderId, BigDecimal newStop, BigDecimal newTarget) {
                            return OrderResult.accepted(brokerOrderId, null, "replaced");
                        }

                        @Override
                        public OrderResult flatten(String symbol) {
                            return OrderResult.accepted("oid-2", null, "accepted");
                        }

                        @Override public List<Position> positions() { return List.of(); }

                        @Override
                        public List<de.visterion.agora.trading.Order> orders(String status) { return List.of(); }

                        @Override
                        public Account account() {
                            return new Account("acc-1", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, "USD", "ACTIVE");
                        }

                        @Override
                        public de.visterion.agora.trading.Order orderByClientRef(String clientRef) {
                            return new de.visterion.agora.trading.Order("oid-1", clientRef, "AAPL", "buy", BigDecimal.ONE, "limit", "new");
                        }

                        @Override
                        public OrderResult cancel(String brokerOrderId) {
                            return OrderResult.accepted(brokerOrderId, null, "canceled");
                        }

                        @Override
                        public void probe() {}
                    };
                }
            };
        }
    }

    private static final String VALID_BODY = """
            {"connection":"test-conn","symbol":"AAPL","side":"buy","qty":1,"stopLossStop":95,"takeProfitLimit":110,"clientRef":"ref-1"}
            """;

    @Test
    void tradingTokenAccepted_returnsAcceptedWithSentinelOrderId() {
        RestClient http = RestClient.create();
        ResponseEntity<String> resp = http.post()
                .uri("http://localhost:" + port + "/tools/place_bracket")
                .header("Authorization", "Bearer trade-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(VALID_BODY)
                .retrieve()
                .toEntity(String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        String body = resp.getBody();
        assertThat(body).contains("\"accepted\":true");
        // Sentinel: proves stub answered (real Alpaca would never return exactly "oid-1")
        assertThat(body).contains("\"orderId\":\"oid-1\"");
    }

    @Test
    void generalTokenRejected_returns401() {
        RestClient http = RestClient.create();
        try {
            http.post()
                    .uri("http://localhost:" + port + "/tools/place_bracket")
                    .header("Authorization", "Bearer gen-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(VALID_BODY)
                    .retrieve()
                    .toBodilessEntity();
            fail("expected 401");
        } catch (HttpClientErrorException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(401);
        }
    }

    @Test
    void noBearerRejected_returns401() {
        RestClient http = RestClient.create();
        try {
            http.post()
                    .uri("http://localhost:" + port + "/tools/place_bracket")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(VALID_BODY)
                    .retrieve()
                    .toBodilessEntity();
            fail("expected 401");
        } catch (HttpClientErrorException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(401);
        }
    }
}
