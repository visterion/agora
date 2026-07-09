package de.visterion.agora.trading.saxo;

import de.visterion.agora.trading.*;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Saxo OpenAPI broker provider (SIM or LIVE per connection config). Trades by Uic —
 * symbol resolution lives in SaxoInstrumentResolver. Auth is a bearer token from the
 * per-connection SaxoTokenStore; an expired session maps to UNAVAILABLE with a re-auth
 * hint, never to a reject. Saxo 403 is an auth problem (unlike Alpaca, where 403 is
 * a rejected order).
 */
public class SaxoBrokerProvider implements BrokerProvider {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConnectionConfig cfg;
    private final SaxoTokenStore store;
    private final RestClient client;
    private final SaxoInstrumentResolver resolver;
    private volatile AccountContext accountContext;

    record AccountContext(String clientKey, String accountKey) {}

    SaxoBrokerProvider(ConnectionConfig cfg, SaxoTokenStore store, RestClient client,
                        SaxoInstrumentResolver resolver) {
        this.cfg = cfg;
        this.store = store;
        this.client = client;
        this.resolver = resolver;
    }

    @Override public String name() { return "saxo"; }

    String bearer() {
        return store.authorizationHeaderValue();
    }

    // ---- probe ----

    @Override
    public void probe() {
        try {
            client.get().uri("/root/v1/user").header("Authorization", bearer())
                    .retrieve().toBodilessEntity();
        } catch (BrokerException e) {
            throw e;
        } catch (Exception e) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "Saxo probe failed: " + e.getMessage(), e);
        }
    }

    // ---- account context ----

    AccountContext accountContext() {
        AccountContext ctx = accountContext;
        if (ctx != null) return ctx;
        JsonNode resp = getJson("/port/v1/accounts/me");
        JsonNode data = resp.path("Data");
        if (!data.isArray() || data.isEmpty()) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE, "no saxo accounts", null);
        }
        JsonNode chosen;
        String wanted = cfg.getExtra() == null ? null : cfg.getExtra().get("account-key");
        if (data.size() == 1 && wanted == null) {
            chosen = data.get(0);
        } else if (wanted != null) {
            chosen = null;
            for (JsonNode n : data) {
                if (wanted.equals(n.path("AccountKey").asString(null))) { chosen = n; break; }
            }
            if (chosen == null) {
                throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                        "configured account-key not found among saxo accounts", null);
            }
        } else {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "multiple saxo accounts — set extra.account-key on the connection", null);
        }
        ctx = new AccountContext(chosen.path("ClientKey").asString(""), chosen.path("AccountKey").asString(""));
        this.accountContext = ctx;
        return ctx;
    }

    // ---- reads ----

    @Override
    public Account account() {
        AccountContext ctx = accountContext();
        // RestClient's DefaultUriBuilderFactory runs in TEMPLATE_AND_VALUES mode: a URI
        // string passed to uri(String) is treated as a template and re-encoded wholesale,
        // which double-encodes an already-percent-encoded query string. Query values bound
        // via build(Object...) template variables are, by contrast, encoded exactly once
        // (strictly, per RFC 3986) — so '+' in ClientKey/AccountKey becomes %2B on the wire
        // instead of round-tripping as a literal '+' (which a server would decode as space).
        JsonNode n = getJson(b -> b.path("/port/v1/balances")
                .queryParam("ClientKey", "{ck}")
                .queryParam("AccountKey", "{ak}")
                .build(ctx.clientKey(), ctx.accountKey()));
        return new Account(ctx.accountKey(), bd(n.path("TotalValue")),
                bd(n.path("MarginAvailableForTrading")), bd(n.path("CashBalance")),
                n.path("Currency").asString("USD"), "ACTIVE");
    }

    @Override
    public List<Position> positions() {
        AccountContext ctx = accountContext();
        // See comment in account() re: TEMPLATE_AND_VALUES encoding.
        JsonNode resp = getJson(b -> b.path("/port/v1/netpositions")
                .queryParam("ClientKey", "{ck}")
                .queryParam("AccountKey", "{ak}")
                .queryParam("FieldGroups", "{fg}")
                .build(ctx.clientKey(), ctx.accountKey(), "NetPositionBase,NetPositionView,DisplayAndFormat"));
        List<Position> out = new ArrayList<>();
        for (JsonNode n : resp.path("Data")) {
            JsonNode base = n.path("NetPositionBase");
            JsonNode view = n.path("NetPositionView");
            // Saxo has no CurrentMarketValue; Exposure = mark-to-market when price feed is live (0 with delayed SIM data)
            out.add(new Position(
                    baseSymbol(n.path("DisplayAndFormat").path("Symbol").asString("")),
                    bd(base.path("Amount")),
                    bd(view.path("AverageOpenPrice")),
                    bd(view.path("Exposure")),
                    bd(view.path("ProfitLossOnTrade")),
                    view.path("ExposureCurrency").asString(
                            n.path("DisplayAndFormat").path("Currency").asString("USD"))));
        }
        return out;
    }

    /**
     * Flattens each bracket parent's embedded {@code RelatedOpenOrders} legs into the
     * returned list as their own {@link Order} entries with {@code parentId} set to the
     * parent's OrderId, so {@code get_orders} exposes legs individually — mirrors the leg
     * detection already used by {@link #modifyBracket}. filledQty/avgFillPrice are left
     * null for every Saxo order: {@code /port/v1/orders/me} is an *open*-orders endpoint and
     * we could not verify, without live credentials, a reliable fill-qty/fill-price field
     * on it — documented as a gap in exit-tools.md rather than guessed.
     */
    @Override
    public List<Order> orders(String status) {
        JsonNode resp = getJson("/port/v1/orders/me?fieldGroups=DisplayAndFormat");
        List<Order> out = new ArrayList<>();
        for (JsonNode n : resp.path("Data")) {
            Order parent = parseOrder(n, null);
            if (status == null || status.isBlank() || parent.status().equalsIgnoreCase(status)) {
                out.add(parent);
            }
            JsonNode children = n.path("RelatedOpenOrders");
            if (children.isArray()) {
                for (JsonNode c : children) {
                    Order leg = parseOrder(c, parent.brokerOrderId());
                    if (status == null || status.isBlank() || leg.status().equalsIgnoreCase(status)) {
                        out.add(leg);
                    }
                }
            }
        }
        return out;
    }

    @Override
    public Order orderByClientRef(String clientRef) {
        for (Order o : orders(null)) {
            if (clientRef != null && clientRef.equals(o.clientRef())) return o;
        }
        throw new BrokerException(BrokerException.Kind.NOT_FOUND, "Order not found: " + clientRef, null);
    }

    // ---- writes: Task 7 (submitBracket/cancel/flatten); modifyBracket is Task 8 ----

    @Override
    public OrderResult submitBracket(BracketOrderRequest req) {
        SaxoInstrumentResolver.ResolvedInstrument ri;
        try {
            ri = resolver.resolve(req.symbol());
        } catch (SaxoInstrumentResolver.SymbolResolutionException e) {
            return OrderResult.rejected(e.getMessage(), "SYMBOL");
        }
        AccountContext ctx = accountContext();
        String side = capitalize(req.side());
        String opposite = opposite(side);
        boolean limit = req.limitPrice() != null;
        boolean slLimit = req.stopLossLimit() != null;

        ObjectNode body = MAPPER.createObjectNode();
        body.put("Uic", ri.uic());
        body.put("AssetType", ri.assetType());
        body.put("BuySell", side);
        body.put("Amount", req.qty());
        body.put("OrderType", limit ? "Limit" : "Market");
        if (limit) body.put("OrderPrice", req.limitPrice());
        body.put("ManualOrder", false);
        body.put("AccountKey", ctx.accountKey());
        if (req.clientRef() != null) body.put("ExternalReference", req.clientRef());
        body.set("OrderDuration", durationNode(mapTif(req.timeInForce())));

        ObjectNode takeProfit = MAPPER.createObjectNode();
        takeProfit.put("OrderType", "Limit");
        takeProfit.put("OrderPrice", req.takeProfitLimit());
        takeProfit.put("BuySell", opposite);
        takeProfit.put("Amount", req.qty());
        takeProfit.put("ManualOrder", false);
        takeProfit.put("AccountKey", ctx.accountKey());
        takeProfit.set("OrderDuration", durationNode("GoodTillCancel"));

        ObjectNode stopLoss = MAPPER.createObjectNode();
        stopLoss.put("OrderType", slLimit ? "StopLimit" : "StopIfTraded");
        stopLoss.put("OrderPrice", req.stopLossStop());
        if (slLimit) stopLoss.put("StopLimitPrice", req.stopLossLimit());
        stopLoss.put("BuySell", opposite);
        stopLoss.put("Amount", req.qty());
        stopLoss.put("ManualOrder", false);
        stopLoss.put("AccountKey", ctx.accountKey());
        stopLoss.set("OrderDuration", durationNode("GoodTillCancel"));

        var children = MAPPER.createArrayNode();
        children.add(takeProfit);
        children.add(stopLoss);
        body.set("Orders", children);

        String requestId = req.clientRef() != null ? req.clientRef() : UUID.randomUUID().toString();

        try {
            JsonNode resp = client.post().uri("/trade/v2/orders")
                    .header("Authorization", bearer())
                    .header("X-Request-ID", requestId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve().body(JsonNode.class);
            String orderId = resp == null ? null : resp.path("OrderId").asString(null);
            return withLegIds(orderId, req.clientRef());
        } catch (RestClientResponseException e) {
            return writeError(e);
        } catch (Exception e) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "saxo submitBracket failed: " + e.getMessage(), e);
        }
    }

    /**
     * Best-effort follow-up: Saxo's placement response carries only the parent OrderId
     * (never child leg ids — unlike Alpaca), so to hand the caller SL/TP leg ids for a
     * later per-leg modify_bracket, we re-fetch /port/v1/orders/me (same lookup
     * modifyBracket already does) and read the parent's embedded RelatedOpenOrders. If
     * this lookup fails or the parent isn't found yet (e.g. eventual consistency), the
     * placement itself must still be reported accepted — leg ids simply stay null.
     */
    private OrderResult withLegIds(String orderId, String clientRef) {
        if (orderId == null) return OrderResult.accepted(orderId, clientRef, "accepted");
        try {
            JsonNode resp = getJson("/port/v1/orders/me");
            for (JsonNode n : resp.path("Data")) {
                if (orderId.equals(n.path("OrderId").asString(null))) {
                    JsonNode children = n.path("RelatedOpenOrders");
                    String slLeg = null;
                    String tpLeg = null;
                    if (children.isArray()) {
                        for (JsonNode c : children) {
                            String type = c.path("OpenOrderType").asString("");
                            if (type.contains("Stop")) slLeg = c.path("OrderId").asString(null);
                            else if ("Limit".equals(type)) tpLeg = c.path("OrderId").asString(null);
                        }
                    }
                    if (slLeg != null || tpLeg != null) {
                        return OrderResult.accepted(orderId, clientRef, "accepted", slLeg, tpLeg);
                    }
                    break;
                }
            }
        } catch (Exception ignored) {
            // best-effort only — leg lookup failing must not fail the placement itself
        }
        return OrderResult.accepted(orderId, clientRef, "accepted");
    }

    /**
     * SIM-verified (see saxo-sim-spike.md Q2/Q3): pre-fill, only the parent shows up in
     * /port/v1/orders/me (OrderRelation "IfDoneMaster"); its children are EMBEDDED in
     * RelatedOpenOrders — not separate top-level orders — so we fetch raw JsonNode (not
     * parseOrder) to reach that array. There is no MasterOrderId anywhere; post-fill the
     * parent id vanishes entirely and legs become sibling-referencing Oco orders, which is
     * out of scope for v1 (NOT_FOUND, matching "filled orders' protection legs must be
     * modified individually"). Each present leg is PATCHed individually with the SIM-minimal
     * body (no Uic/Amount/BuySell/ManualOrder) using the child's own OpenOrderType/Duration.
     */
    @Override
    public OrderResult modifyBracket(String id, String symbol, BigDecimal stop, BigDecimal target) {
        // Guard: both params null → nothing to modify
        if (stop == null && target == null) {
            return OrderResult.rejected("nothing to modify — provide stop and/or target", "NO_CHANGES");
        }

        AccountContext ctx = accountContext();
        JsonNode resp = getJson("/port/v1/orders/me");
        JsonNode parent = null;
        for (JsonNode n : resp.path("Data")) {
            if (id.equals(n.path("OrderId").asString(null))) { parent = n; break; }
        }
        JsonNode children = parent == null ? null : parent.path("RelatedOpenOrders");
        if (parent == null || !children.isArray() || children.isEmpty()) {
            OrderResult fb = modifyBySymbolFallback(ctx, symbol, stop, target, id);
            if (fb != null) return fb;
            throw new BrokerException(BrokerException.Kind.NOT_FOUND,
                    "no open bracket parent or working legs for " + id + " / " + symbol, null);
        }

        JsonNode slLeg = null;
        JsonNode tpLeg = null;
        for (JsonNode child : children) {
            String type = child.path("OpenOrderType").asString("");
            if (type.contains("Stop")) slLeg = child;
            else if ("Limit".equals(type)) tpLeg = child;
        }

        // Guard: stop requested but no SL leg found
        if (stop != null && slLeg == null) {
            return OrderResult.rejected("no stop-loss leg on bracket " + id, "LEG_NOT_FOUND");
        }
        // Guard: target requested but no TP leg found
        if (target != null && tpLeg == null) {
            return OrderResult.rejected("no take-profit leg on bracket " + id, "LEG_NOT_FOUND");
        }

        if (stop != null && slLeg != null) {
            OrderResult r = patchLeg(ctx, slLeg, stop);
            if (!r.accepted()) return r;
        }
        if (target != null && tpLeg != null) {
            OrderResult r = patchLeg(ctx, tpLeg, target);
            if (!r.accepted()) return r;
        }
        return OrderResult.accepted(id, null, "replaced");
    }

    /**
     * Post-fill: the parent is gone; find the detached protective orders by resolved Uic and patch them.
     * <p>Assumption: this fallback targets ONE bracket's detached protective legs per symbol (Dracul holds
     * one position per symbol). It excludes the caller's own parent id ({@code id}) from the Uic-matching
     * scan — otherwise a "parent found but its RelatedOpenOrders is empty" state (the parent still appears
     * in {@code /port/v1/orders/me} sharing the resolved Uic) could self-misclassify the entry order as a
     * stop/take-profit leg and corrupt its price instead of correctly falling through to NOT_FOUND.
     */
    private OrderResult modifyBySymbolFallback(AccountContext ctx, String symbol,
                                               BigDecimal stop, BigDecimal target, String id) {
        long uic;
        try { uic = resolver.resolve(symbol).uic(); }
        catch (SaxoInstrumentResolver.SymbolResolutionException e) { return OrderResult.rejected(e.getMessage(), "SYMBOL"); }
        JsonNode resp = getJson("/port/v1/orders/me");
        JsonNode slLeg = null, tpLeg = null;
        for (JsonNode n : resp.path("Data")) {
            if (id.equals(n.path("OrderId").asString(null))) continue;
            if (n.path("Uic").asLong(-1) != uic) continue;
            String type = n.path("OpenOrderType").asString("");
            if (type.contains("Stop")) slLeg = n;
            else if ("Limit".equals(type)) tpLeg = n;
        }
        if (stop != null && slLeg == null) return null;   // let caller 404 uniformly
        if (target != null && tpLeg == null) return null;
        if (stop != null) { OrderResult r = patchLeg(ctx, slLeg, stop); if (!r.accepted()) return r; }
        if (target != null) { OrderResult r = patchLeg(ctx, tpLeg, target); if (!r.accepted()) return r; }
        return OrderResult.accepted(id, null, "replaced");
    }

    private OrderResult patchLeg(AccountContext ctx, JsonNode child, BigDecimal newPrice) {
        String durationType = child.path("Duration").path("DurationType").asString("GoodTillCancel");
        ObjectNode body = MAPPER.createObjectNode();
        body.put("AccountKey", ctx.accountKey());
        body.put("OrderId", child.path("OrderId").asString(""));
        body.put("AssetType", "Stock");
        body.put("OrderType", child.path("OpenOrderType").asString(""));
        body.put("OrderPrice", newPrice);
        body.set("OrderDuration", durationNode(durationType));
        try {
            client.patch().uri("/trade/v2/orders")
                    .header("Authorization", bearer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve().body(JsonNode.class);
            return OrderResult.accepted(null, null, "replaced");
        } catch (RestClientResponseException e) {
            return writeError(e);
        } catch (Exception e) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "saxo modifyBracket failed: " + e.getMessage(), e);
        }
    }

    /**
     * Saxo has no true partial-close endpoint: this places a single opposite-side Market
     * order for the requested close quantity, exactly as a full flatten does but for
     * {@code amount.abs() * fraction} (or {@code qty} directly). Requested qty > available
     * position is rejected (without ever calling the broker) rather than silently clamped.
     * A fraction that truncates to zero shares (e.g. a small fraction on a 1-share position)
     * is likewise rejected — Saxo requires whole-share amounts for stocks and this provider
     * does simple truncation (no lot-size table), documented as a limitation in
     * exit-tools.md. avgFillPrice is always null: a Market order's placement response
     * carries no synchronous fill price.
     */
    @Override
    public OrderResult flatten(String symbol, BigDecimal fraction, BigDecimal qty) {
        SaxoInstrumentResolver.ResolvedInstrument ri;
        try {
            ri = resolver.resolve(symbol);
        } catch (SaxoInstrumentResolver.SymbolResolutionException e) {
            return OrderResult.rejected(e.getMessage(), "SYMBOL");
        }
        AccountContext ctx = accountContext();
        JsonNode resp = getJson(b -> b.path("/port/v1/netpositions")
                .queryParam("ClientKey", "{ck}")
                .queryParam("AccountKey", "{ak}")
                .queryParam("FieldGroups", "{fg}")
                .build(ctx.clientKey(), ctx.accountKey(), "NetPositionBase,NetPositionView,DisplayAndFormat"));

        JsonNode match = null;
        for (JsonNode n : resp.path("Data")) {
            JsonNode base = n.path("NetPositionBase");
            if (base.path("Uic").asLong(-1) == ri.uic() && bd(base.path("Amount")).signum() != 0) {
                match = n;
                break;
            }
        }
        if (match == null) {
            throw new BrokerException(BrokerException.Kind.NOT_FOUND, "no open position: " + symbol, null);
        }
        BigDecimal amount = bd(match.path("NetPositionBase").path("Amount"));
        BigDecimal available = amount.abs();
        String opposite = amount.signum() > 0 ? "Sell" : "Buy";

        BigDecimal closeQty;
        if (qty != null) {
            closeQty = qty;
            if (closeQty.compareTo(available) > 0) {
                return OrderResult.rejected(
                        "requested qty " + closeQty.toPlainString() + " exceeds position " + available.toPlainString(),
                        "QTY_EXCEEDS_POSITION");
            }
        } else if (fraction != null) {
            closeQty = available.multiply(fraction).setScale(0, java.math.RoundingMode.DOWN);
            if (closeQty.signum() == 0) {
                return OrderResult.rejected(
                        "fraction " + fraction.toPlainString() + " of position " + available.toPlainString()
                                + " truncates to 0 shares", "QTY_ROUNDED_TO_ZERO");
            }
        } else {
            closeQty = available;
        }

        ObjectNode body = MAPPER.createObjectNode();
        body.put("Uic", ri.uic());
        body.put("AssetType", ri.assetType());
        body.put("BuySell", opposite);
        body.put("Amount", closeQty);
        body.put("OrderType", "Market");
        body.put("ManualOrder", false);
        body.put("AccountKey", ctx.accountKey());
        body.set("OrderDuration", durationNode("DayOrder"));

        try {
            JsonNode resp2 = client.post().uri("/trade/v2/orders")
                    .header("Authorization", bearer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve().body(JsonNode.class);
            String orderId = resp2 == null ? null : resp2.path("OrderId").asString(null);
            BigDecimal remainingQty = available.subtract(closeQty);
            return OrderResult.accepted(orderId, null, "accepted", closeQty, remainingQty, null);
        } catch (RestClientResponseException e) {
            return writeError(e);
        } catch (Exception e) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "saxo flatten failed: " + e.getMessage(), e);
        }
    }

    @Override
    public OrderResult cancel(String brokerOrderId) {
        AccountContext ctx = accountContext();
        try {
            // See account()/positions() re: TEMPLATE_AND_VALUES encoding — AccountKey is
            // bound as a build(Object...) template variable, never concatenated/URLEncoder-escaped.
            client.delete()
                    .uri(b -> b.path("/trade/v2/orders/{id}")
                            .queryParam("AccountKey", "{ak}")
                            .build(brokerOrderId, ctx.accountKey()))
                    .header("Authorization", bearer())
                    .retrieve().toBodilessEntity();
            return OrderResult.accepted(brokerOrderId, null, "canceled");
        } catch (BrokerException e) {
            throw e;
        } catch (RestClientResponseException e) {
            throw readError(e);
        } catch (Exception e) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "saxo cancel failed: " + e.getMessage(), e);
        }
    }

    private static String capitalize(String side) {
        if (side == null || side.isEmpty()) return side;
        return Character.toUpperCase(side.charAt(0)) + side.substring(1).toLowerCase(Locale.ROOT);
    }

    private static String opposite(String side) {
        return "Buy".equals(side) ? "Sell" : "Buy";
    }

    private static String mapTif(String tif) {
        if (tif == null) return "GoodTillCancel";
        return switch (tif.toLowerCase(Locale.ROOT)) {
            case "gtc" -> "GoodTillCancel";
            case "day" -> "DayOrder";
            default -> "GoodTillCancel";
        };
    }

    private static ObjectNode durationNode(String durationType) {
        ObjectNode d = MAPPER.createObjectNode();
        d.put("DurationType", durationType);
        return d;
    }

    /**
     * Error mapping for order-placement writes (submitBracket/flatten): 400 → parsed
     * ErrorInfo → OrderResult.rejected (an order-level rejection, not an outage); 409 →
     * UNAVAILABLE (duplicate X-Request-ID replay); everything else delegates to readError
     * (404 → NOT_FOUND, 401/403 → UNAVAILABLE re-auth hint, else → UNAVAILABLE).
     */
    private static OrderResult writeError(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == 400) {
            JsonNode errorBody = parseErrorBody(e);
            String message = errorBody.path("ErrorInfo").path("Message").asString(null);
            if (message == null) message = errorBody.path("Message").asString(null);
            if (message == null) message = rawBody(e);
            String code = errorBody.path("ErrorInfo").path("ErrorCode").asString(null);
            if (code == null) code = String.valueOf(status);
            return OrderResult.rejected(message, code);
        }
        if (status == 409) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "saxo duplicate request (X-Request-ID replay?)", e);
        }
        throw readError(e);
    }

    private static JsonNode parseErrorBody(RestClientResponseException e) {
        try {
            byte[] body = e.getResponseBodyAsByteArray();
            if (body != null && body.length > 0) return MAPPER.readTree(body);
        } catch (Exception ignored) { /* fall through to raw/status fallback */ }
        return MAPPER.createObjectNode();
    }

    private static String rawBody(RestClientResponseException e) {
        String raw = e.getResponseBodyAsString();
        return (raw == null || raw.isBlank()) ? e.getMessage() : raw;
    }

    // ---- helpers ----

    JsonNode getJson(String uri) {
        return exchange(() -> client.get().uri(uri).header("Authorization", bearer())
                .retrieve().body(JsonNode.class));
    }

    JsonNode getJson(Function<UriBuilder, URI> uriFn) {
        return exchange(() -> client.get().uri(uriFn).header("Authorization", bearer())
                .retrieve().body(JsonNode.class));
    }

    private JsonNode exchange(Supplier<JsonNode> call) {
        try {
            JsonNode n = call.get();
            if (n == null) {
                throw new BrokerException(BrokerException.Kind.UNAVAILABLE, "empty saxo response", null);
            }
            return n;
        } catch (BrokerException e) {
            throw e;
        } catch (RestClientResponseException e) {
            throw readError(e);
        } catch (Exception e) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "saxo request failed: " + e.getMessage(), e);
        }
    }

    static BrokerException readError(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == 404) {
            return new BrokerException(BrokerException.Kind.NOT_FOUND, "Resource not found (HTTP 404)", e);
        }
        if (status == 401 || status == 403) {
            return new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "saxo auth failed (HTTP " + status + ") — re-authorize via /auth/saxo/login", e);
        }
        return new BrokerException(BrokerException.Kind.UNAVAILABLE, "saxo HTTP " + status, e);
    }

    /**
     * {@code parentId} is non-null when {@code n} is a leg embedded in a parent's
     * {@code RelatedOpenOrders} (see {@link #orders}); role is then derived from the leg's
     * own {@code OpenOrderType} (contains "Stop" → stop_loss, "Limit" → take_profit) —
     * the same pattern {@link #modifyBracket} already uses to find SL/TP legs. A top-level
     * node's role is "entry" when its {@code OrderRelation} is "IfDoneMaster" (a bracket
     * parent), else "other". filledQty/avgFillPrice are always null here — see the gap note
     * on {@link #orders}.
     */
    static Order parseOrder(JsonNode n, String parentId) {
        String type = n.path("OpenOrderType").asString("").toLowerCase(Locale.ROOT);
        return new Order(
                n.path("OrderId").asString(""),
                n.path("ExternalReference").asString(null),
                baseSymbol(n.path("DisplayAndFormat").path("Symbol").asString("")),
                n.path("BuySell").asString("").toLowerCase(Locale.ROOT),
                bd(n.path("Amount")),
                type,
                n.path("Status").asString("").toLowerCase(Locale.ROOT),
                deriveRole(n, type, parentId), null, null, parentId);
    }

    private static String deriveRole(JsonNode n, String type, String parentId) {
        if (parentId != null) {
            if (type.contains("stop")) return "stop_loss";
            if (type.equals("limit")) return "take_profit";
            return "other";
        }
        return "IfDoneMaster".equals(n.path("OrderRelation").asString("")) ? "entry" : "other";
    }

    /** "AAPL:xnas" → "AAPL" (Saxo symbols carry the exchange suffix). */
    static String baseSymbol(String saxoSymbol) {
        int i = saxoSymbol.indexOf(':');
        return i < 0 ? saxoSymbol : saxoSymbol.substring(0, i);
    }

    static BigDecimal bd(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return BigDecimal.ZERO;
        if (node.isNumber()) return node.decimalValue();
        try { return new BigDecimal(node.asString("0")); }
        catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }
}
