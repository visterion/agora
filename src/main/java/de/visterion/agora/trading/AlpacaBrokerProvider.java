package de.visterion.agora.trading;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Alpaca broker provider (paper or live per connection config).
 * Auth via APCA-API-KEY-ID / APCA-API-SECRET-KEY default headers.
 * 3-outcome mapping: 2xx→accepted, 403/422→rejected, 404→NOT_FOUND, else→UNAVAILABLE.
 */
public class AlpacaBrokerProvider implements BrokerProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient client;

    /** Instances are built by AlpacaBrokerProviderFactory, one per active connection. */
    AlpacaBrokerProvider(String baseUrl, String keyId, String secret, long timeoutMs) {
        this.client = RestClient.builder()
                .requestFactory(TradingHttp.requestFactory(timeoutMs))
                .baseUrl(baseUrl)
                .defaultHeader("APCA-API-KEY-ID", keyId)
                .defaultHeader("APCA-API-SECRET-KEY", secret)
                .build();
    }

    /** Default-timeout convenience ctor (tests). */
    AlpacaBrokerProvider(String baseUrl, String keyId, String secret) {
        this(baseUrl, keyId, secret, TradingHttp.DEFAULT_TIMEOUT_MS);
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
            return parseAcceptedBracket(resp);
        } catch (RestClientResponseException e) {
            return handleWriteError(e);
        } catch (Exception e) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "Alpaca submitBracket failed: " + e.getMessage(), e);
        }
    }

    @Override
    public OrderResult modifyBracket(String brokerOrderId, String symbol, BigDecimal newStop, BigDecimal newTarget) {
        LegIds legs = resolveLegsByParent(brokerOrderId);
        if (legs == null) legs = resolveLegsBySymbol(brokerOrderId, symbol); // Task 3 provides this
        if (newStop != null) {
            if (legs == null || legs.slLegId() == null)
                return OrderResult.rejected("no stop-loss leg on bracket " + brokerOrderId, "LEG_NOT_FOUND");
            OrderResult r = patchLeg(legs.slLegId(), "stop_price", newStop);
            if (!r.accepted()) return r;
        }
        if (newTarget != null) {
            if (legs == null || legs.tpLegId() == null)
                return OrderResult.rejected("no take-profit leg on bracket " + brokerOrderId, "LEG_NOT_FOUND");
            OrderResult r = patchLeg(legs.tpLegId(), "limit_price", newTarget);
            if (!r.accepted()) return r;
        }
        return OrderResult.accepted(brokerOrderId, null, "replaced");
    }

    private record LegIds(String slLegId, String tpLegId) {}

    /** GET the bracket parent (nested) and classify its legs; null if the parent order isn't found (e.g. post-fill). */
    private LegIds resolveLegsByParent(String parentId) {
        try {
            JsonNode n = client.get()
                    .uri(uri -> uri.path("/orders/{id}").queryParam("nested", "true").build(parentId))
                    .retrieve().body(JsonNode.class);
            JsonNode legs = n == null ? null : n.path("legs");
            if (legs == null || !legs.isArray() || legs.isEmpty()) return null;
            return classifyLegs(legs);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) return null;
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "Alpaca modifyBracket lookup failed HTTP " + e.getStatusCode().value(), e);
        } catch (Exception e) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "Alpaca modifyBracket lookup failed: " + e.getMessage(), e);
        }
    }

    /**
     * Post-fill fallback: list working orders for the symbol and classify the protective legs.
     * <p>Defensive hardening (mirrors the Saxo fallback): excludes the bracket parent id from
     * classification. The self-match window is narrower on Alpaca than on Saxo — a gone parent
     * isn't normally still "open" here — but the exclusion is correct and cheap.
     */
    private LegIds resolveLegsBySymbol(String parentId, String symbol) {
        try {
            JsonNode arr = client.get()
                    .uri(uri -> uri.path("/orders").queryParam("status", "open")
                            .queryParam("symbols", symbol).queryParam("nested", "false").build())
                    .retrieve().body(JsonNode.class);
            if (arr == null || !arr.isArray() || arr.isEmpty()) return null;
            return classifyLegs(parentId, arr);
        } catch (RestClientResponseException e) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "Alpaca modifyBracket symbol fallback failed HTTP " + e.getStatusCode().value(), e);
        } catch (Exception e) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "Alpaca modifyBracket lookup failed: " + e.getMessage(), e);
        }
    }

    private static LegIds classifyLegs(JsonNode legs) {
        return classifyLegs(null, legs);
    }

    private static LegIds classifyLegs(String excludeId, JsonNode legs) {
        String sl = null, tp = null;
        for (JsonNode leg : legs) {
            if (excludeId != null && excludeId.equals(leg.path("id").asString(null))) continue;
            String type = leg.path("type").asString("");
            if (type.equals("stop") || type.equals("stop_limit")) sl = leg.path("id").asString(null);
            else if (type.equals("limit")) tp = leg.path("id").asString(null);
        }
        return new LegIds(sl, tp);
    }

    private OrderResult patchLeg(String legId, String priceField, BigDecimal price) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put(priceField, price.toPlainString());
        try {
            JsonNode resp = client.patch()
                    .uri("/orders/{id}", legId)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(JsonNode.class);
            return parseAccepted(resp);
        } catch (RestClientResponseException e) {
            return handleWriteError(e);
        } catch (Exception e) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE, "Alpaca patchLeg failed: " + e.getMessage(), e);
        }
    }

    /**
     * Full or partial close. Alpaca's {@code DELETE /positions/{symbol}} accepts either a
     * {@code qty} query param (share count) or a {@code percentage} param (0-100, exclusive
     * of the boundary conventions Alpaca documents) — never both. {@code fraction} is
     * converted to a percentage (fraction*100); {@code qty} is passed through verbatim.
     * Alpaca validates qty/percentage against the live position itself (a qty that exceeds
     * the position comes back as a 422/403, mapped below to {@code OrderResult.rejected} —
     * this provider does not pre-fetch the position to duplicate that check).
     */
    @Override
    public OrderResult flatten(String symbol, BigDecimal fraction, BigDecimal qty) {
        try {
            JsonNode resp = client.delete()
                    .uri(uri -> {
                        var b = uri.path("/positions/{symbol}");
                        if (qty != null) {
                            b = b.queryParam("qty", qty.toPlainString());
                        } else if (fraction != null) {
                            b = b.queryParam("percentage",
                                    fraction.multiply(new BigDecimal(100)).stripTrailingZeros().toPlainString());
                        }
                        return b.build(symbol);
                    })
                    .retrieve()
                    .body(JsonNode.class);
            // Alpaca returns the closing order; parse it if present, else accepted
            if (resp != null && resp.hasNonNull("id")) {
                OrderResult base = parseAcceptedFlatten(resp);
                BigDecimal remaining = remainingAfterClose(symbol);
                return OrderResult.accepted(base.brokerOrderId(), base.clientRef(), base.status(),
                        base.closedQty(), remaining, base.avgFillPrice());
            }
            return OrderResult.accepted(null, null, "accepted", null, null, null);
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

    @Override
    public void probe() {
        try {
            client.get().uri("/clock").retrieve().toBodilessEntity();
        } catch (Exception e) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "Alpaca probe failed: " + e.getMessage(), e);
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

    /**
     * Requests {@code nested=true} so a bracket parent's response carries its stop-loss/
     * take-profit legs in a {@code legs[]} array; those legs are flattened into the
     * returned list as their own {@link Order} entries with {@code parentId} set to the
     * parent's brokerOrderId, so {@code get_orders} exposes legs individually (not just
     * bracket parents) — see documentation/exit-tools.md.
     */
    @Override
    public List<Order> orders(String status) {
        try {
            JsonNode resp = client.get()
                    .uri(uri -> {
                        var b = uri.path("/orders").queryParam("nested", "true");
                        if (status != null && !status.isBlank()) b = b.queryParam("status", status);
                        return b.build();
                    })
                    .retrieve()
                    .body(JsonNode.class);
            List<Order> out = new ArrayList<>();
            if (resp != null && resp.isArray()) {
                for (JsonNode n : resp) {
                    out.add(parseOrder(n, null));
                    JsonNode legs = n.path("legs");
                    if (legs.isArray()) {
                        String parentId = n.path("id").asString(null);
                        for (JsonNode leg : legs) out.add(parseOrder(leg, parentId));
                    }
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
            return parseOrder(n, null);
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

    /** Parse a 2xx order response into an accepted OrderResult (modifyBracket path — no legs). */
    private static OrderResult parseAccepted(JsonNode n) {
        if (n == null) return OrderResult.accepted(null, null, "accepted");
        String id = n.path("id").asString(null);
        String clientOrderId = n.path("client_order_id").asString(null);
        String status = n.path("status").asString("accepted");
        return OrderResult.accepted(id, clientOrderId, status);
    }

    /**
     * Parse a submitBracket 2xx response, extracting the SL/TP leg ids from Alpaca's
     * {@code legs[]} array (present on a bracket order's create response): a leg with
     * {@code type} "stop"/"stop_limit" is the stop-loss, "limit" is the take-profit.
     */
    private static OrderResult parseAcceptedBracket(JsonNode n) {
        if (n == null) return OrderResult.accepted(null, null, "accepted");
        String id = n.path("id").asString(null);
        String clientOrderId = n.path("client_order_id").asString(null);
        String status = n.path("status").asString("accepted");
        String stopLegId = null;
        String takeProfitLegId = null;
        JsonNode legs = n.path("legs");
        if (legs.isArray()) {
            for (JsonNode leg : legs) {
                String type = leg.path("type").asString("");
                if (type.equals("stop") || type.equals("stop_limit")) {
                    stopLegId = leg.path("id").asString(null);
                } else if (type.equals("limit")) {
                    takeProfitLegId = leg.path("id").asString(null);
                }
            }
        }
        if (stopLegId != null || takeProfitLegId != null) {
            return OrderResult.accepted(id, clientOrderId, status, stopLegId, takeProfitLegId);
        }
        return OrderResult.accepted(id, clientOrderId, status);
    }

    /**
     * Parse a flatten (DELETE /positions/{symbol}) response: the closing order object.
     * {@code qty} is the requested close size; {@code filled_avg_price} is present only
     * once the closing order actually fills (often not synchronous with the DELETE call),
     * hence nullable. Alpaca's response carries no "remaining position size" field; the
     * caller backfills it with a follow-up {@code GET /positions/{symbol}} (see
     * {@link #remainingAfterClose(String)}).
     */
    private static OrderResult parseAcceptedFlatten(JsonNode n) {
        String id = n.path("id").asString(null);
        String clientOrderId = n.path("client_order_id").asString(null);
        String status = n.path("status").asString("accepted");
        BigDecimal closedQty = n.hasNonNull("qty") ? bd(n.path("qty")) : null;
        BigDecimal avgFillPrice = n.hasNonNull("filled_avg_price") ? bd(n.path("filled_avg_price")) : null;
        return OrderResult.accepted(id, clientOrderId, status, closedQty, null, avgFillPrice);
    }

    /**
     * After a close, the live remaining position size (0 when the position is fully gone).
     * This is an OPTIONAL follow-up read that runs after the close already succeeded, so it
     * must never fail the close: a 404 (position gone) maps to {@code ZERO}; any other
     * failure — non-404 HTTP status, or a connection/IO error such as
     * {@link org.springframework.web.client.ResourceAccessException} — maps to {@code null}
     * (remaining size unknown, not fabricated).
     */
    private BigDecimal remainingAfterClose(String symbol) {
        try {
            JsonNode n = client.get().uri("/positions/{s}", symbol).retrieve().body(JsonNode.class);
            return (n != null && n.hasNonNull("qty")) ? bd(n.path("qty")) : null;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) return BigDecimal.ZERO;
            return null; // don't fail the close over a follow-up read
        } catch (Exception e) {
            return null; // don't fail the close over a follow-up read (e.g. connection/IO error)
        }
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

    /**
     * Parse a JSON node into a neutral Order DTO. Alpaca uses "order_type" for the type
     * field in list responses, "type" in create responses. {@code parentId} is non-null
     * when this node is a bracket leg flattened out of a parent's {@code legs[]} (see
     * {@link #orders}); role is then derived from the leg's own type (stop/stop_limit →
     * stop_loss, limit → take_profit). For a top-level node, role is "entry" when Alpaca's
     * {@code order_class} marks it as a bracket/oco/oto parent, else "other".
     */
    private static Order parseOrder(JsonNode n, String parentId) {
        String type = n.path("order_type").isMissingNode()
                ? n.path("type").asString("")
                : n.path("order_type").asString("");
        String role = deriveRole(n, type, parentId);
        BigDecimal filledQty = n.hasNonNull("filled_qty") ? bd(n.path("filled_qty")) : null;
        BigDecimal avgFillPrice = n.hasNonNull("filled_avg_price") ? bd(n.path("filled_avg_price")) : null;
        return new Order(
                n.path("id").asString(""),
                n.path("client_order_id").asString(null),
                n.path("symbol").asString(""),
                n.path("side").asString(""),
                bd(n.path("qty")),
                type,
                n.path("status").asString(""),
                role, filledQty, avgFillPrice, parentId
        );
    }

    private static String deriveRole(JsonNode n, String type, String parentId) {
        if (parentId != null) {
            if (type.equals("stop") || type.equals("stop_limit")) return "stop_loss";
            if (type.equals("limit")) return "take_profit";
            return "other";
        }
        String orderClass = n.path("order_class").asString("");
        if (orderClass.equals("bracket") || orderClass.equals("oco") || orderClass.equals("oto")) return "entry";
        return "other";
    }

    private static BigDecimal bd(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return BigDecimal.ZERO;
        try { return new BigDecimal(node.asString("0")); }
        catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }
}
