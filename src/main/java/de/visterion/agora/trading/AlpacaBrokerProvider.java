package de.visterion.agora.trading;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Alpaca paper-trading broker provider.
 * Auth via APCA-API-KEY-ID / APCA-API-SECRET-KEY default headers.
 * 3-outcome mapping: 2xx→accepted, 403/422→rejected, 404→NOT_FOUND, else→UNAVAILABLE.
 */
@Component
public class AlpacaBrokerProvider implements BrokerProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient client;

    /**
     * Primary constructor — package-private so tests can construct directly.
     * Spring uses it via @Autowired with @Value bindings.
     */
    @Autowired
    AlpacaBrokerProvider(
            @Value("${agora.trading.connections.alpaca-paper.base-url}") String baseUrl,
            @Value("${agora.trading.connections.alpaca-paper.key-id}") String keyId,
            @Value("${agora.trading.connections.alpaca-paper.secret}") String secret) {
        this.client = RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(
                        HttpClients.custom().disableAutomaticRetries().build()))
                .baseUrl(baseUrl)
                .defaultHeader("APCA-API-KEY-ID", keyId)
                .defaultHeader("APCA-API-SECRET-KEY", secret)
                .build();
    }

    @Override
    public String name() { return "alpaca"; }

    // ---- Write operations ----

    @Override
    public OrderResult submitBracket(BracketOrderRequest req) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("symbol", req.symbol());
        body.put("qty", req.qty().toPlainString());
        body.put("side", req.side());
        body.put("type", req.type());
        body.put("time_in_force", req.timeInForce());
        if (req.limitPrice() != null) body.put("limit_price", req.limitPrice().toPlainString());
        body.put("order_class", "bracket");
        body.put("client_order_id", req.clientRef());

        ObjectNode stopLoss = MAPPER.createObjectNode();
        stopLoss.put("stop_price", req.stopLossStop().toPlainString());
        if (req.stopLossLimit() != null) stopLoss.put("limit_price", req.stopLossLimit().toPlainString());
        body.set("stop_loss", stopLoss);

        ObjectNode takeProfit = MAPPER.createObjectNode();
        takeProfit.put("limit_price", req.takeProfitLimit().toPlainString());
        body.set("take_profit", takeProfit);

        try {
            JsonNode resp = client.post()
                    .uri("/orders")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            return parseAccepted(resp);
        } catch (RestClientResponseException e) {
            return handleWriteError(e);
        } catch (Exception e) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "Alpaca submitBracket failed: " + e.getMessage(), e);
        }
    }

    @Override
    public OrderResult modifyBracket(String brokerOrderId, BigDecimal newStop, BigDecimal newTarget) {
        ObjectNode body = MAPPER.createObjectNode();
        if (newStop != null) body.put("stop_price", newStop.toPlainString());
        if (newTarget != null) body.put("limit_price", newTarget.toPlainString());

        try {
            JsonNode resp = client.patch()
                    .uri("/orders/{id}", brokerOrderId)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            return parseAccepted(resp);
        } catch (RestClientResponseException e) {
            return handleWriteError(e);
        } catch (Exception e) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "Alpaca modifyBracket failed: " + e.getMessage(), e);
        }
    }

    @Override
    public OrderResult flatten(String symbol) {
        try {
            JsonNode resp = client.delete()
                    .uri("/positions/{symbol}", symbol)
                    .retrieve()
                    .body(JsonNode.class);
            // Alpaca returns the closing order; parse it if present, else accepted
            if (resp != null && resp.hasNonNull("id")) {
                return parseAccepted(resp);
            }
            return OrderResult.accepted(null, null, "accepted");
        } catch (RestClientResponseException e) {
            return handleWriteError(e);
        } catch (Exception e) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "Alpaca flatten failed: " + e.getMessage(), e);
        }
    }

    @Override
    public OrderResult cancel(String brokerOrderId) {
        try {
            client.delete()
                    .uri("/orders/{id}", brokerOrderId)
                    .retrieve()
                    .toBodilessEntity();
            return OrderResult.accepted(brokerOrderId, null, "canceled");
        } catch (RestClientResponseException e) {
            return handleWriteError(e);
        } catch (Exception e) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "Alpaca cancel failed: " + e.getMessage(), e);
        }
    }

    // ---- Read operations ----

    @Override
    public List<Position> positions() {
        try {
            JsonNode resp = client.get()
                    .uri("/positions")
                    .retrieve()
                    .body(JsonNode.class);
            List<Position> out = new ArrayList<>();
            if (resp != null && resp.isArray()) {
                for (JsonNode n : resp) {
                    out.add(new Position(
                            n.path("symbol").asString(""),
                            bd(n.path("qty")),
                            bd(n.path("avg_entry_price")),
                            bd(n.path("market_value")),
                            bd(n.path("unrealized_pl")),
                            n.path("currency").asString("USD")
                    ));
                }
            }
            return out;
        } catch (RestClientResponseException e) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "Alpaca positions failed HTTP " + e.getStatusCode().value(), e);
        } catch (Exception e) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "Alpaca positions failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Order> orders(String status) {
        try {
            JsonNode resp = client.get()
                    .uri(uri -> {
                        var b = uri.path("/orders");
                        if (status != null && !status.isBlank()) b = b.queryParam("status", status);
                        return b.build();
                    })
                    .retrieve()
                    .body(JsonNode.class);
            List<Order> out = new ArrayList<>();
            if (resp != null && resp.isArray()) {
                for (JsonNode n : resp) {
                    out.add(parseOrder(n));
                }
            }
            return out;
        } catch (RestClientResponseException e) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "Alpaca orders failed HTTP " + e.getStatusCode().value(), e);
        } catch (Exception e) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "Alpaca orders failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Account account() {
        try {
            JsonNode n = client.get()
                    .uri("/account")
                    .retrieve()
                    .body(JsonNode.class);
            if (n == null) throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "Empty account response", null);
            return new Account(
                    n.path("id").asString(""),
                    bd(n.path("equity")),
                    bd(n.path("buying_power")),
                    bd(n.path("cash")),
                    n.path("currency").asString("USD"),
                    n.path("status").asString("")
            );
        } catch (BrokerException e) {
            throw e;
        } catch (RestClientResponseException e) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "Alpaca account failed HTTP " + e.getStatusCode().value(), e);
        } catch (Exception e) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "Alpaca account failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Order orderByClientRef(String clientRef) {
        try {
            JsonNode n = client.get()
                    .uri(uri -> uri.path("/orders:by_client_order_id")
                            .queryParam("client_order_id", clientRef)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            if (n == null) throw new BrokerException(BrokerException.Kind.NOT_FOUND,
                    "Order not found: " + clientRef, null);
            return parseOrder(n);
        } catch (BrokerException e) {
            throw e;
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 404) {
                throw new BrokerException(BrokerException.Kind.NOT_FOUND,
                        "Order not found: " + clientRef, e);
            }
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "Alpaca orderByClientRef failed HTTP " + status, e);
        } catch (Exception e) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "Alpaca orderByClientRef failed: " + e.getMessage(), e);
        }
    }

    // ---- Helpers ----

    /** Parse a 2xx order response into an accepted OrderResult. */
    private static OrderResult parseAccepted(JsonNode n) {
        if (n == null) return OrderResult.accepted(null, null, "accepted");
        String id = n.path("id").asString(null);
        String clientOrderId = n.path("client_order_id").asString(null);
        String status = n.path("status").asString("accepted");
        return OrderResult.accepted(id, clientOrderId, status);
    }

    /**
     * Maps RestClient error responses for write operations:
     * 403/422 → rejected; 404 → NOT_FOUND; else → UNAVAILABLE.
     */
    private static OrderResult handleWriteError(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == 403 || status == 422) {
            String message = extractMessage(e);
            return OrderResult.rejected(message, String.valueOf(status));
        }
        if (status == 404) {
            throw new BrokerException(BrokerException.Kind.NOT_FOUND,
                    "Resource not found (HTTP 404)", e);
        }
        throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                "Alpaca returned HTTP " + status, e);
    }

    /** Try to extract "message" from the JSON error body, fallback to status text. */
    private static String extractMessage(RestClientResponseException e) {
        try {
            byte[] body = e.getResponseBodyAsByteArray();
            if (body != null && body.length > 0) {
                JsonNode node = MAPPER.readTree(body);
                JsonNode msg = node.path("message");
                if (!msg.isMissingNode() && !msg.isNull()) return msg.asString("");
            }
        } catch (Exception ignored) { /* fall through */ }
        return e.getMessage();
    }

    /** Parse a JSON node into a neutral Order DTO. Alpaca uses "order_type" for the type field. */
    private static Order parseOrder(JsonNode n) {
        return new Order(
                n.path("id").asString(""),
                n.path("client_order_id").asString(null),
                n.path("symbol").asString(""),
                n.path("side").asString(""),
                bd(n.path("qty")),
                // Alpaca uses "order_type" in list responses, "type" in create responses
                n.path("order_type").isMissingNode()
                        ? n.path("type").asString("")
                        : n.path("order_type").asString(""),
                n.path("status").asString("")
        );
    }

    private static BigDecimal bd(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return BigDecimal.ZERO;
        try { return new BigDecimal(node.asString("0")); }
        catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }
}
