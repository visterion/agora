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
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: proves the live-token gate over real HTTP end-to-end —
 * BearerTokenFilter sets the presented token as a request attribute, LiveAccessGuard
 * reads it back via RequestContextHolder, and BrokerService.resolve() uses it to decide
 * whether a LIVE connection is visible. Earlier coverage only exercised this wiring with
 * injected Supplier stubs; this test drives it through a real servlet request instead.
 *
 * <p>A stub BrokerProvider returns a deterministic {@code OrderResult.accepted("live-oid-1", ...)}.
 * The sentinel "live-oid-1" proves the stub answered.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "agora.auth.tokens=gen-token",
                "agora.trading.tokens=trade-token",
                "agora.trading.live-tokens=live-token",
                "agora.trading.connections.test-paper.provider=stub",
                "agora.trading.connections.test-paper.environment=paper",
                "agora.trading.connections.test-paper.key-id=k",
                "agora.trading.connections.test-paper.secret=s",
                "agora.trading.connections.test-live.provider=stub",
                "agora.trading.connections.test-live.environment=live",
                "agora.trading.connections.test-live.key-id=k",
                "agora.trading.connections.test-live.secret=s"
        })
class LiveConnectionGateWebhookIT {

    @LocalServerPort
    int port;

    /**
     * Stub BrokerProviderFactory registered under provider key "stub" so ConnectionRegistry
     * resolves both "test-paper" and "test-live" to this stub instead of a real Alpaca provider.
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
                            return OrderResult.accepted("live-oid-1", req.clientRef(), "accepted");
                        }

                        @Override
                        public OrderResult modifyBracket(String brokerOrderId, BigDecimal newStop, BigDecimal newTarget) {
                            return OrderResult.accepted(brokerOrderId, null, "replaced");
                        }

                        @Override
                        public OrderResult flatten(String symbol, BigDecimal fraction, BigDecimal qty) {
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
                            return new de.visterion.agora.trading.Order("live-oid-1", clientRef, "AAPL", "buy", BigDecimal.ONE, "limit", "new");
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

    private static String bracketBody(String connection) {
        return """
                {"connection":"%s","symbol":"AAPL","side":"buy","qty":1,"stopLossStop":95,"takeProfitLimit":110,"clientRef":"ref-1"}
                """.formatted(connection);
    }

    private ResponseEntity<String> postTool(String tool, String bearer, String body) {
        RestClient http = RestClient.create();
        return http.post()
                .uri("http://localhost:" + port + "/tools/" + tool)
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class);
    }

    @Test
    void liveTokenOverRealHttp_routesToLiveConnection() {
        ResponseEntity<String> resp = postTool("place_bracket", "live-token", bracketBody("test-live"));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        String body = resp.getBody();
        assertThat(body).contains("\"accepted\":true");
        // Sentinel: proves the stub answered on the live connection
        assertThat(body).contains("\"orderId\":\"live-oid-1\"");
    }

    @Test
    void tradingTokenOverRealHttp_liveConnectionInvisible_noEnumerationOracle() {
        ResponseEntity<String> resp = postTool("place_bracket", "trade-token", bracketBody("test-live"));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        String body = resp.getBody();
        assertThat(body).contains("\"available\":false");
        assertThat(body).contains("unknown or inactive connection: test-live");
        assertThat(body).contains("(active: test-paper)");
        // No enumeration oracle: the error must not reveal that "test-live" exists but is gated
        assertThat(body).doesNotContain("live connection requires");
    }

    @Test
    void tradingTokenOverRealHttp_paperConnectionStillWorks() {
        ResponseEntity<String> resp = postTool("place_bracket", "trade-token", bracketBody("test-paper"));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        String body = resp.getBody();
        assertThat(body).contains("\"accepted\":true");
    }

    @Test
    void listConnectionsOverRealHttp_scopedByToken() {
        ResponseEntity<String> tradeResp = postTool("list_connections", "trade-token", "{}");
        assertThat(tradeResp.getStatusCode().value()).isEqualTo(200);
        String tradeBody = tradeResp.getBody();
        assertThat(tradeBody).contains("test-paper");
        assertThat(tradeBody).doesNotContain("test-live");

        ResponseEntity<String> liveResp = postTool("list_connections", "live-token", "{}");
        assertThat(liveResp.getStatusCode().value()).isEqualTo(200);
        String liveBody = liveResp.getBody();
        assertThat(liveBody).contains("test-paper");
        assertThat(liveBody).contains("test-live");
    }
}
