package de.visterion.agora.trading.saxo;

import de.visterion.agora.trading.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

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
    private volatile AccountContext accountContext;

    record AccountContext(String clientKey, String accountKey) {}

    SaxoBrokerProvider(ConnectionConfig cfg, SaxoTokenStore store, RestClient client) {
        this.cfg = cfg;
        this.store = store;
        this.client = client;
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
        String encoded = "/port/v1/balances?ClientKey=" + encodeQueryParam(ctx.clientKey())
                + "&AccountKey=" + encodeQueryParam(ctx.accountKey());
        JsonNode n = getJson(encoded);
        return new Account(ctx.accountKey(), bd(n.path("TotalValue")),
                bd(n.path("MarginAvailableForTrading")), bd(n.path("CashBalance")),
                n.path("Currency").asString("USD"), "ACTIVE");
    }

    @Override
    public List<Position> positions() {
        AccountContext ctx = accountContext();
        String encoded = "/port/v1/netpositions?ClientKey=" + encodeQueryParam(ctx.clientKey())
                + "&AccountKey=" + encodeQueryParam(ctx.accountKey())
                + "&FieldGroups=" + encodeQueryParam("NetPositionBase,NetPositionView,DisplayAndFormat");
        JsonNode resp = getJson(encoded);
        List<Position> out = new ArrayList<>();
        for (JsonNode n : resp.path("Data")) {
            JsonNode base = n.path("NetPositionBase");
            JsonNode view = n.path("NetPositionView");
            out.add(new Position(
                    baseSymbol(n.path("DisplayAndFormat").path("Symbol").asString("")),
                    bd(base.path("Amount")),
                    bd(view.path("AverageOpenPrice")),
                    bd(view.path("CurrentMarketValue")),
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

    // ---- writes: Tasks 7/8 ----

    @Override
    public OrderResult submitBracket(BracketOrderRequest req) { throw notYet("submitBracket"); }
    @Override
    public OrderResult modifyBracket(String id, BigDecimal stop, BigDecimal target) { throw notYet("modifyBracket"); }
    @Override
    public OrderResult flatten(String symbol) { throw notYet("flatten"); }
    @Override
    public OrderResult cancel(String brokerOrderId) { throw notYet("cancel"); }

    private static BrokerException notYet(String op) {
        return new BrokerException(BrokerException.Kind.UNAVAILABLE,
                "saxo " + op + " not implemented yet", null);
    }

    // ---- helpers ----

    private static String encodeQueryParam(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    JsonNode getJson(URI uri) {
        try {
            JsonNode n = client.get().uri(uri).header("Authorization", bearer())
                    .retrieve().body(JsonNode.class);
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

    JsonNode getJson(String uri) {
        try {
            JsonNode n = client.get().uri(uri).header("Authorization", bearer())
                    .retrieve().body(JsonNode.class);
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
