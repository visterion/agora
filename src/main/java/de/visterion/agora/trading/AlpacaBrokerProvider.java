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
        this.client = TradingHttp.clientBuilder(timeoutMs)
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
        // M-T5: a null clientRef must not be serialized as an explicit "client_order_id":null —
        // that would erase the only idempotency key a caller could use to detect a duplicate
        // submit after a transport-level timeout.
        if (req.clientRef() != null) body.put("client_order_id", req.clientRef());

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
            // M-T5: on a transport-level failure (no HTTP response to classify — timeout,
            // connection reset, etc.) the caller cannot tell whether the order was actually
            // placed. If a clientRef was supplied, point at the idempotent way to check.
            String hint = req.clientRef() != null
                    ? " (order may already exist — verify via get_order_by_ref before retrying)"
                    : "";
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "Alpaca submitBracket failed: " + e.getMessage() + hint, e);
        }
    }

    private static final String AMBIGUOUS_LEGS = "AMBIGUOUS_LEGS";

    @Override
    public OrderResult modifyBracket(String brokerOrderId, String symbol, BigDecimal newStop, BigDecimal newTarget) {
        LegIds legs = resolveLegsByParent(brokerOrderId);
        if (legs == null) legs = resolveLegsBySymbol(brokerOrderId, symbol);

        boolean slMoved = false;
        if (newStop != null) {
            if (legs == null || legs.slLegId() == null) {
                if (legs != null && legs.slAmbiguous()) return ambiguousLegsRejection(symbol);
                return OrderResult.rejected("no stop-loss leg on bracket " + brokerOrderId, "LEG_NOT_FOUND");
            }
            OrderResult r = patchLeg(legs.slLegId(), "stop_price", newStop);
            if (!r.accepted()) return r;
            slMoved = true;
        }
        if (newTarget != null) {
            if (legs == null || legs.tpLegId() == null) {
                OrderResult rejection = (legs != null && legs.tpAmbiguous())
                        ? ambiguousLegsRejection(symbol)
                        : OrderResult.rejected("no take-profit leg on bracket " + brokerOrderId, "LEG_NOT_FOUND");
                return slMoved ? withAlreadyMovedStop(rejection, newStop) : rejection;
            }
            OrderResult r = patchLeg(legs.tpLegId(), "limit_price", newTarget);
            if (!r.accepted()) return slMoved ? withAlreadyMovedStop(r, newStop) : r;
        }
        return OrderResult.accepted(brokerOrderId, null, "replaced");
    }

    private static OrderResult ambiguousLegsRejection(String symbol) {
        return OrderResult.rejected(
                "cannot uniquely resolve bracket legs for " + symbol
                        + " — multiple candidate orders; modify legs individually by order id",
                AMBIGUOUS_LEGS);
    }

    /** M-T1: fold "stop-loss already moved" into a rejection that followed a successful SL patch. */
    private static OrderResult withAlreadyMovedStop(OrderResult rejection, BigDecimal newStop) {
        return OrderResult.rejected(
                "take-profit update failed AFTER stop-loss was already moved to " + newStop.toPlainString()
                        + ": " + rejection.rejectReason(),
                rejection.rejectCode());
    }

    /**
     * @param slAmbiguous true when the symbol-fallback found more than one stop-loss candidate
     *                    (never set by the direct parent lookup, which has ground truth). Zero
     *                    candidates is NOT ambiguous — it leaves {@code slLegId} null and routes
     *                    through the existing LEG_NOT_FOUND path instead.
     * @param tpAmbiguous same for the take-profit candidate.
     */
    private record LegIds(String slLegId, String tpLegId, boolean slAmbiguous, boolean tpAmbiguous) {
        private static LegIds known(String sl, String tp) { return new LegIds(sl, tp, false, false); }
    }

    /** GET the bracket parent (nested) and classify its legs; null if the parent order isn't found (e.g. post-fill). */
    private LegIds resolveLegsByParent(String parentId) {
        try {
            JsonNode n = client.get()
                    .uri(uri -> uri.path("/orders/{id}").queryParam("nested", "true").build(parentId))
                    .retrieve().body(JsonNode.class);
            JsonNode legs = n == null ? null : n.path("legs");
            if (legs == null || !legs.isArray() || legs.isEmpty()) return null;
            String sl = null, tp = null;
            for (JsonNode leg : legs) {
                String type = leg.path("type").asString("");
                if (type.equals("stop") || type.equals("stop_limit")) sl = leg.path("id").asString(null);
                else if (type.equals("limit")) tp = leg.path("id").asString(null);
            }
            return LegIds.known(sl, tp);
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
     * Post-fill fallback (C5): list open orders for the symbol (nested, so an unfilled bracket
     * entry's own legs[] is visible) and classify ONLY orders that are safe to treat as a
     * detached protective leg:
     * <ul>
     *   <li>not the (gone) bracket parent id itself;</li>
     *   <li>{@code order_class} bracket/oco — plain standalone orders are never a bracket leg;</li>
     *   <li>NO non-empty {@code legs[]} of their own — an order carrying legs is an unfilled
     *       bracket ENTRY, never a protective leg (this is the C5 fix: the old code let a
     *       resting bracket-B entry limit order masquerade as bracket-A's take-profit).</li>
     * </ul>
     * If more than one candidate matches a leg type, that leg cannot be safely resolved and is
     * flagged ambiguous — {@link #modifyBracket} refuses rather than guessing. If zero candidates
     * match, the leg is simply not found (not ambiguous) and {@link #modifyBracket} takes its
     * existing LEG_NOT_FOUND path.
     */
    private LegIds resolveLegsBySymbol(String parentId, String symbol) {
        try {
            JsonNode arr = client.get()
                    .uri(uri -> uri.path("/orders").queryParam("status", "open")
                            .queryParam("symbols", symbol).queryParam("nested", "true").build())
                    .retrieve().body(JsonNode.class);
            if (arr == null || !arr.isArray() || arr.isEmpty()) return null;

            List<String> slCandidates = new ArrayList<>();
            List<String> tpCandidates = new ArrayList<>();
            for (JsonNode n : arr) {
                String id = n.path("id").asString(null);
                if (parentId != null && parentId.equals(id)) continue;
                JsonNode ownLegs = n.path("legs");
                if (ownLegs.isArray() && !ownLegs.isEmpty()) continue; // unfilled bracket ENTRY, never a leg
                String orderClass = n.path("order_class").asString("");
                if (!orderClass.equals("bracket") && !orderClass.equals("oco")) continue;
                String type = n.path("type").asString("");
                if (type.equals("stop") || type.equals("stop_limit")) slCandidates.add(id);
                else if (type.equals("limit")) tpCandidates.add(id);
            }
            if (slCandidates.isEmpty() && tpCandidates.isEmpty()) return null;

            String slLegId = slCandidates.size() == 1 ? slCandidates.get(0) : null;
            String tpLegId = tpCandidates.size() == 1 ? tpCandidates.get(0) : null;
            // Zero candidates is "not found", not "ambiguous" — only more than one candidate is
            // genuinely ambiguous. Conflating the two here previously made modifyBracket report
            // AMBIGUOUS_LEGS ("multiple candidate orders") when there were in fact none.
            boolean slAmbiguous = slCandidates.size() > 1;
            boolean tpAmbiguous = tpCandidates.size() > 1;
            return new LegIds(slLegId, tpLegId, slAmbiguous, tpAmbiguous);
        } catch (RestClientResponseException e) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "Alpaca modifyBracket symbol fallback failed HTTP " + e.getStatusCode().value(), e);
        } catch (Exception e) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "Alpaca modifyBracket lookup failed: " + e.getMessage(), e);
        }
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
     * the position comes back as a 422/403, mapped below to {@code OrderResult.rejected}).
     *
     * <p>M-T3: the position's live quantity is fetched BEFORE the DELETE (not read back
     * afterwards, which races the fill and normally just returns the pre-close qty). {@code
     * remainingQty = preQty − closedQty}, floored at zero; a full close (no fraction/qty)
     * is always zero by definition. A 404 on the pre-fetch means there was nothing to close.
     */
    @Override
    public OrderResult flatten(String symbol, BigDecimal fraction, BigDecimal qty) {
        if (symbol == null || symbol.isBlank())
            throw new IllegalArgumentException("symbol must not be blank");

        BigDecimal preQty = fetchPositionQtyOrThrow(symbol);
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
                BigDecimal remaining = remainingQtyAfterClose(preQty, fraction, qty, base.closedQty());
                return OrderResult.accepted(base.brokerOrderId(), base.clientRef(), base.status(),
                        base.closedQty(), remaining, base.avgFillPrice());
            }
            BigDecimal remaining = remainingQtyAfterClose(preQty, fraction, qty, null);
            return OrderResult.accepted(null, null, "accepted", null, remaining, null);
        } catch (RestClientResponseException e) {
            return handleWriteError(e);
        } catch (Exception e) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "Alpaca flatten failed: " + e.getMessage(), e);
        }
    }

    /**
     * Pre-close position quantity. A required pre-condition (not a best-effort follow-up like
     * the old post-close read was) — any failure here aborts the close rather than fabricating
     * a remaining quantity. A 404 means there is nothing open to flatten.
     */
    private BigDecimal fetchPositionQtyOrThrow(String symbol) {
        try {
            JsonNode n = client.get().uri("/positions/{s}", symbol).retrieve().body(JsonNode.class);
            return (n != null && n.hasNonNull("qty")) ? bd(n.path("qty")) : BigDecimal.ZERO;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new BrokerException(BrokerException.Kind.NOT_FOUND, "no open position", e);
            }
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "Alpaca flatten pre-fetch failed HTTP " + e.getStatusCode().value(), e);
        } catch (Exception e) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "Alpaca flatten pre-fetch failed: " + e.getMessage(), e);
        }
    }

    /** remaining = preQty - closed, floored at zero; a full close (no fraction/qty) is always zero. */
    private static BigDecimal remainingQtyAfterClose(BigDecimal preQty, BigDecimal fraction, BigDecimal qty,
                                                       BigDecimal closedQtyFromResponse) {
        if (fraction == null && qty == null) return BigDecimal.ZERO;
        BigDecimal closed = closedQtyFromResponse != null ? closedQtyFromResponse : qty;
        if (closed == null) return null;
        BigDecimal remaining = preQty.subtract(closed);
        return remaining.max(BigDecimal.ZERO);
    }

    @Override
    public OrderResult cancel(String brokerOrderId) {
        if (brokerOrderId == null || brokerOrderId.isBlank())
            throw new IllegalArgumentException("brokerOrderId must not be blank");
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
                            null,
                            bd(n.path("qty")),
                            bd(n.path("avg_entry_price")),
                            bd(n.path("market_value")),
                            bd(n.path("unrealized_pl")),
                            n.path("currency").asString("USD"),
                            n.path("asset_class").asString(null),
                            null
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

    private static final int ORDERS_PAGE_LIMIT = 500;

    /**
     * Requests {@code nested=true} so a bracket parent's response carries its stop-loss/
     * take-profit legs in a {@code legs[]} array; those legs are flattened into the
     * returned list as their own {@link Order} entries with {@code parentId} set to the
     * parent's brokerOrderId, so {@code get_orders} exposes legs individually (not just
     * bracket parents) — see documentation/exit-tools.md.
     *
     * <p>M-T2: paginates with keyset pagination ({@code limit=500}, {@code direction=asc},
     * {@code after=<submitted_at of the last top-level order on the page>}) until a page
     * returns fewer than {@value #ORDERS_PAGE_LIMIT} rows — Alpaca's default page size (50)
     * would otherwise silently truncate reconciliation.
     */
    @Override
    public List<Order> orders(String status) {
        try {
            List<Order> out = new ArrayList<>();
            String after = null;
            while (true) {
                final String afterParam = after;
                JsonNode resp = client.get()
                        .uri(uri -> {
                            var b = uri.path("/orders").queryParam("nested", "true")
                                    .queryParam("limit", String.valueOf(ORDERS_PAGE_LIMIT))
                                    .queryParam("direction", "asc");
                            if (status != null && !status.isBlank()) b = b.queryParam("status", status);
                            if (afterParam != null) b = b.queryParam("after", afterParam);
                            return b.build();
                        })
                        .retrieve()
                        .body(JsonNode.class);

                int pageSize = 0;
                String lastSubmittedAt = null;
                if (resp != null && resp.isArray()) {
                    pageSize = resp.size();
                    for (JsonNode n : resp) {
                        out.add(parseOrder(n, null));
                        JsonNode legs = n.path("legs");
                        if (legs.isArray()) {
                            String parentId = n.path("id").asString(null);
                            for (JsonNode leg : legs) out.add(parseOrder(leg, parentId));
                        }
                        String submittedAt = n.path("submitted_at").asString(null);
                        if (submittedAt != null) lastSubmittedAt = submittedAt;
                    }
                }
                if (pageSize < ORDERS_PAGE_LIMIT || lastSubmittedAt == null) break;
                after = lastSubmittedAt;
            }
            return out;
        } catch (RestClientResponseException e) {
            int status2 = e.getStatusCode().value();
            if (status2 == 400 || status2 == 422) {
                throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                        "Alpaca orders failed: invalid order query (HTTP " + status2 + ")", e);
            }
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "Alpaca orders failed HTTP " + status2, e);
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
     * hence nullable. Alpaca's response carries no "remaining position size" field — the
     * caller (see {@link #flatten}) computes it from the pre-close position quantity fetched
     * BEFORE the DELETE (M-T3: a post-close read races the fill and normally just returns the
     * pre-close qty, so it is never used).
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

    /**
     * M-T4: a top-level {@code order_class} bracket/oco node is only "entry" while it still
     * carries its own non-empty {@code legs[]} (the realistic shape of an unfilled bracket
     * parent). Without legs of its own, the node is itself a working protective leg — e.g.
     * fetched standalone via {@link #orderByClientRef} or listed post-fill — and role must be
     * derived from its own {@code type}, exactly like a leg flattened out of a parent's
     * {@code legs[]}. Failing to do this mislabels an orphaned stop-loss/take-profit "entry".
     */
    private static String deriveRole(JsonNode n, String type, String parentId) {
        if (parentId != null) {
            if (type.equals("stop") || type.equals("stop_limit")) return "stop_loss";
            if (type.equals("limit")) return "take_profit";
            return "other";
        }
        String orderClass = n.path("order_class").asString("");
        if (orderClass.equals("bracket") || orderClass.equals("oco")) {
            JsonNode legs = n.path("legs");
            if (legs.isArray() && !legs.isEmpty()) return "entry";
            if (type.equals("stop") || type.equals("stop_limit")) return "stop_loss";
            if (type.equals("limit")) return "take_profit";
        }
        return "other";
    }

    private static BigDecimal bd(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return BigDecimal.ZERO;
        try { return new BigDecimal(node.asString("0")); }
        catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }
}
