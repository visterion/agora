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
        return store.validAccessToken().map(t -> "Bearer " + t).orElseThrow(() ->
                new BrokerException(BrokerException.Kind.UNAVAILABLE,
                        "saxo connection not authorized — visit /auth/saxo/login?connection="
                                + store.connectionId(), null));
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

    @Override
    public List<Order> orders(String status) {
        JsonNode resp = getJson("/port/v1/orders/me?fieldGroups=DisplayAndFormat");
        List<Order> out = new ArrayList<>();
        for (JsonNode n : resp.path("Data")) {
            Order o = parseOrder(n);
            if (status == null || status.isBlank()
                    || o.status().equalsIgnoreCase(status)) {
                out.add(o);
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
            return OrderResult.accepted(orderId, req.clientRef(), "accepted");
        } catch (RestClientResponseException e) {
            return writeError(e);
        } catch (Exception e) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "saxo submitBracket failed: " + e.getMessage(), e);
        }
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
    public OrderResult modifyBracket(String id, BigDecimal stop, BigDecimal target) {
        AccountContext ctx = accountContext();
        JsonNode resp = getJson("/port/v1/orders/me");
        JsonNode parent = null;
        for (JsonNode n : resp.path("Data")) {
            if (id.equals(n.path("OrderId").asString(null))) { parent = n; break; }
        }
        JsonNode children = parent == null ? null : parent.path("RelatedOpenOrders");
        if (parent == null || !children.isArray() || children.isEmpty()) {
            throw new BrokerException(BrokerException.Kind.NOT_FOUND,
                    "no open bracket parent: " + id
                            + " (filled orders' protection legs must be modified individually)", null);
        }

        JsonNode slLeg = null;
        JsonNode tpLeg = null;
        for (JsonNode child : children) {
            String type = child.path("OpenOrderType").asString("");
            if (type.contains("Stop")) slLeg = child;
            else if ("Limit".equals(type)) tpLeg = child;
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

    @Override
    public OrderResult flatten(String symbol) {
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
        String opposite = amount.signum() > 0 ? "Sell" : "Buy";

        ObjectNode body = MAPPER.createObjectNode();
        body.put("Uic", ri.uic());
        body.put("AssetType", ri.assetType());
        body.put("BuySell", opposite);
        body.put("Amount", amount.abs());
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
            return OrderResult.accepted(orderId, null, "accepted");
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

    private static BrokerException notYet(String op) {
        return new BrokerException(BrokerException.Kind.UNAVAILABLE,
                "saxo " + op + " not implemented yet", null);
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

    static Order parseOrder(JsonNode n) {
        return new Order(
                n.path("OrderId").asString(""),
                n.path("ExternalReference").asString(null),
                baseSymbol(n.path("DisplayAndFormat").path("Symbol").asString("")),
                n.path("BuySell").asString("").toLowerCase(Locale.ROOT),
                bd(n.path("Amount")),
                n.path("OpenOrderType").asString("").toLowerCase(Locale.ROOT),
                n.path("Status").asString("").toLowerCase(Locale.ROOT));
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
