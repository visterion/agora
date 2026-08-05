package de.visterion.agora.trading.saxo;

import de.visterion.agora.observability.ProviderLogRedactor;
import de.visterion.agora.trading.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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

    private static final Logger log = LoggerFactory.getLogger(SaxoBrokerProvider.class);

    static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConnectionConfig cfg;
    private final SaxoTokenStore store;
    private final RestClient client;
    private final SaxoInstrumentResolver resolver;
    private volatile AccountContext accountContext;

    /**
     * Bounded-retry knobs for {@link #withLegIds}. Package-private (not final) so
     * {@code SaxoBrokerProviderTest} can zero out the delay — tests must not actually sleep.
     */
    int legLookupMaxAttempts = 3;
    long legLookupDelayMillis = 200;

    /**
     * Wartezeit zwischen dem abgelehnten Bracket-POST und dem Fallback-Entry.
     *
     * <p>Ohne sie feuert der Fallback ~90 ms nach dem Bracket und löst Saxos
     * Order-Rate-Limit selbst aus: in den 14 Tagen vor dem 2026-07-25 kamen 0 von 5
     * Fallback-Versuchen durch, alle mit HTTP 429.
     *
     * <p>Der Wert ist eine HYPOTHESE, kein gemessenes Limit — Saxos genaue Order-Rate ist
     * nicht dokumentiert und wurde nicht ausgemessen. Nach dem Deploy an denselben Logs
     * überprüfbar (grep far-stop): bleibt es bei 429, ist die Wartezeit zu kurz.
     */
    static final long FAR_STOP_DELAY_MS = 1000L;

    /**
     * Effective pre-fallback wait. Package-private (not final) for the same reason as
     * {@link #legLookupDelayMillis}: {@code SaxoBrokerProviderTest} zeroes it so tests
     * never actually sleep.
     */
    long farStopDelayMillis = FAR_STOP_DELAY_MS;

    record AccountContext(String clientKey, String accountKey) {}

    SaxoBrokerProvider(ConnectionConfig cfg, SaxoTokenStore store, RestClient client,
                        SaxoInstrumentResolver resolver) {
        this.cfg = cfg;
        this.store = store;
        this.client = client;
        this.resolver = resolver;
    }

    @Override public String name() { return "saxo"; }

    /** Package-visible for factory/store-keying tests (M-T7). */
    SaxoTokenStore tokenStore() { return store; }

    String bearer() {
        return store.authorizationHeaderValue();
    }

    // ---- probe ----

    @Override
    public void probe() {
        try {
            var resp = client.get().uri("/root/v1/user").header("Authorization", bearer())
                    .retrieve().toBodilessEntity();
            if (log.isDebugEnabled()) {
                log.debug("saxo response [GET /root/v1/user]: status={}", resp.getStatusCode());
            }
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
        JsonNode n = getJson("GET /port/v1/balances", b -> b.path("/port/v1/balances")
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
        JsonNode resp = followPagination(getJson("GET /port/v1/netpositions", b -> b.path("/port/v1/netpositions")
                .queryParam("ClientKey", "{ck}")
                .queryParam("AccountKey", "{ak}")
                .queryParam("FieldGroups", "{fg}")
                .build(ctx.clientKey(), ctx.accountKey(), "NetPositionBase,NetPositionView,DisplayAndFormat")));
        List<Position> out = new ArrayList<>();
        for (JsonNode n : resp.path("Data")) {
            JsonNode base = n.path("NetPositionBase");
            JsonNode view = n.path("NetPositionView");
            BigDecimal qty = bd(base.path("Amount"));
            BigDecimal avgOpen = bd(view.path("AverageOpenPrice"));
            BigDecimal unrealizedPl = bd(view.path("ProfitLossOnTrade"));
            BigDecimal marketValue = marketValue(bd(view.path("Exposure")), qty, avgOpen, unrealizedPl);
            out.add(new Position(
                    baseSymbol(n.path("DisplayAndFormat").path("Symbol").asString("")),
                    textOrNull(n.path("DisplayAndFormat"), "Description"),
                    qty,
                    sideFromAmount(qty),
                    avgOpen,
                    perUnitPrice(marketValue, qty),
                    marketValue,
                    unrealizedPl,
                    view.path("ExposureCurrency").asString(
                            n.path("DisplayAndFormat").path("Currency").asString("USD")),
                    textOrNull(base, "AssetType"),
                    textOrNull(base, "ValueDate"),
                    base.path("OpenOrdersCount").asInt(0)));
        }
        return out;
    }

    /**
     * Maps Saxo {@code GET /port/v1/closedpositions} — the REAL open/close fill prices +
     * realized P/L of a position that has already closed at the broker (e.g. a bracket that
     * filled and stopped out between reconcile cycles), as opposed to {@link #positions()}
     * which only ever sees still-open positions. Same authenticated-call shape as positions()/
     * account(): ClientKey/AccountKey bound as {@code build(Object...)} template variables
     * (never hand-concatenated — see the TEMPLATE_AND_VALUES comment on {@link #account()}).
     *
     * <p>Field mapping (verified against the Saxo OpenAPI reference for {@code ClosedPosition}):
     * {@code Uic} → uic, {@code OpenPrice} → openPrice, {@code ClosingPrice} → closePrice
     * (NOT "ClosePrice"), {@code Amount} → amount, {@code ClosedProfitLoss} → profitLoss
     * (instrument-currency realized P/L; falls back to {@code ProfitLossOnTrade} if absent),
     * symbol from the sibling {@code DisplayAndFormat.Symbol} (stripped of the Saxo exchange
     * suffix, same as positions()/orders()). {@code clientRef} comes from
     * {@code OpeningExternalReferenceId} — the reference of the order that OPENED the
     * position, i.e. the original signal's client reference — falling back to
     * {@code ClosingExternalReferenceId} if the opening one is absent; null if Saxo echoes
     * neither (closed positions do not always carry an external reference). {@code openTime}/
     * {@code closeTime} come straight from {@code ExecutionTimeOpen}/{@code ExecutionTimeClose}
     * (ISO-8601 strings, passed through unparsed). {@code openingPositionId} is the Saxo
     * *position* id from {@code OpeningPositionId} — there is no order id on a closed position,
     * only the id of the position that was opened and later closed.
     *
     * <p>{@code from}/{@code to} apply a client-side TEMPORAL window filter on {@code closeTime}
     * (Saxo's closedpositions endpoint has no server-side date-range query): both bounds are
     * parsed to {@link java.time.Instant} and compared on the instant timeline (not
     * lexicographically), so mixed UTC offsets/precisions compare correctly. A row whose
     * closeTime is missing or unparseable is kept only when neither bound is requested — it is
     * never silently dropped by a range it cannot be judged against.
     */
    @Override
    public List<ClosedPosition> closedPositions() {
        return closedPositions(null, null);
    }

    @Override
    public List<ClosedPosition> closedPositions(String from, String to) {
        AccountContext ctx = accountContext();
        JsonNode resp = followPagination(getJson("GET /port/v1/closedpositions",
                b -> b.path("/port/v1/closedpositions")
                        .queryParam("ClientKey", "{ck}")
                        .queryParam("AccountKey", "{ak}")
                        .queryParam("FieldGroups", "{fg}")
                        .build(ctx.clientKey(), ctx.accountKey(), "ClosedPosition,DisplayAndFormat")));
        List<ClosedPosition> out = new ArrayList<>();
        for (JsonNode n : resp.path("Data")) {
            JsonNode cp = n.path("ClosedPosition");
            String symbol = baseSymbol(n.path("DisplayAndFormat").path("Symbol").asString(""));
            long uic = cp.path("Uic").asLong(-1);
            BigDecimal openPrice = bd(cp.path("OpenPrice"));
            BigDecimal closePrice = bd(cp.path("ClosingPrice"));
            BigDecimal amount = bd(cp.path("Amount"));
            BigDecimal profitLoss = cp.path("ClosedProfitLoss").isMissingNode()
                    ? bd(cp.path("ProfitLossOnTrade")) : bd(cp.path("ClosedProfitLoss"));
            String clientRef = textOrNull(cp, "OpeningExternalReferenceId");
            if (clientRef == null) clientRef = textOrNull(cp, "ClosingExternalReferenceId");
            String openTime = textOrNull(cp, "ExecutionTimeOpen");
            String closeTime = textOrNull(cp, "ExecutionTimeClose");
            Long openingPositionId = parseLongOrNull(textOrNull(cp, "OpeningPositionId"));
            if (!withinCloseWindow(closeTime, from, to)) continue;
            out.add(new ClosedPosition(symbol, uic, openPrice, closePrice, amount, profitLoss,
                    clientRef, openTime, closeTime, openingPositionId));
        }
        return out;
    }

    /** True if closeTime lies within [from,to] on the instant timeline. Null/unparseable closeTime
     *  is kept only when neither bound is set (never silently dropped by a range it can't be judged against). */
    private static boolean withinCloseWindow(String closeTime, String from, String to) {
        boolean ranged = (from != null && !from.isBlank()) || (to != null && !to.isBlank());
        if (!ranged) return true;
        java.time.Instant close = parseInstantOrNull(closeTime);
        if (close == null) return false;
        java.time.Instant lo = parseInstantOrNull(from);
        java.time.Instant hi = parseInstantOrNull(to);
        if (lo != null && close.isBefore(lo)) return false;
        if (hi != null && close.isAfter(hi)) return false;
        return true;
    }

    private static java.time.Instant parseInstantOrNull(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try { return java.time.OffsetDateTime.parse(iso).toInstant(); }
        catch (java.time.format.DateTimeParseException e) {
            try { return java.time.Instant.parse(iso); } catch (Exception ignored) { return null; }
        }
    }

    private static Long parseLongOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    /**
     * Flattens each bracket parent's embedded {@code RelatedOpenOrders} legs into the
     * returned list as their own {@link Order} entries with {@code parentId} set to the
     * parent's OrderId, so {@code get_orders} exposes legs individually — mirrors the leg
     * detection already used by {@link #modifyBracket}. filledQty/avgFillPrice are left
     * null for every Saxo order: {@code /port/v1/orders/me} is an *open*-orders endpoint and
     * we could not verify, without live credentials, a reliable fill-qty/fill-price field
     * on it — documented as a gap in exit-tools.md rather than guessed.
     *
     * <p>Once one leg of a bracket has filled, Saxo starts listing the OCO pair's surviving
     * legs as mutual {@code RelatedOpenOrders}: each leg appears as its own top-level {@code
     * Data} entry (role "other", no parentId, side/clientRef populated) AND as the other
     * leg's embedded child (real role stop_loss/take_profit, parentId set, side/clientRef
     * blank) — live-verified via {@code get_orders} on a filled STT bracket. An unfilled
     * bracket's children are never separately present at top level, so nothing is duplicated
     * there. {@link #mergeDuplicateIds} collapses same-id duplicates into one entry so
     * downstream role/parentId-based logic (protection checks, flatten's opposite-side
     * cancel) doesn't double count or double-act.
     */
    @Override
    public List<Order> orders(String status) {
        return orders(status, null, null);
    }

    @Override
    public List<Order> orders(String status, String from, String to) {
        boolean history = (from != null && !from.isBlank()) || (to != null && !to.isBlank())
                || (status != null && (status.equalsIgnoreCase("closed") || status.equalsIgnoreCase("all")));
        return history ? ordersHistory(status, from, to) : ordersOpen(status);
    }

    private List<Order> ordersOpen(String status) {
        JsonNode resp = followPagination(getJson("/port/v1/orders/me?FieldGroups=DisplayAndFormat"));
        List<Order> raw = new ArrayList<>();
        for (JsonNode n : resp.path("Data")) {
            Order parent = parseOrder(n, null, null);
            raw.add(parent);
            JsonNode children = n.path("RelatedOpenOrders");
            if (children.isArray()) {
                // Legs embedded in RelatedOpenOrders rarely carry their own DisplayAndFormat —
                // fall back to the parent's already-resolved symbol so a leg never surfaces
                // with an empty symbol; only truly orphaned legs (no parent symbol either) get "?".
                for (JsonNode c : children) {
                    raw.add(parseOrder(c, parent.brokerOrderId(), parent.symbol()));
                }
            }
        }
        List<Order> out = new ArrayList<>();
        for (Order o : mergeDuplicateIds(raw)) {
            if (status == null || status.isBlank() || o.status().equalsIgnoreCase(status)) {
                out.add(o);
            }
        }
        return out;
    }

    /**
     * Collapses same-{@code brokerOrderId} duplicates produced by Saxo's mutual {@code
     * RelatedOpenOrders} listing of a filled bracket's surviving OCO pair (see the javadoc on
     * {@link #ordersOpen}) into a single entry per id, preserving first-seen order. Field by
     * field, the more informative value wins — a non-blank {@code parentId}/{@code side}/
     * {@code clientRef}, a role other than "other", a non-null price/fill field — taken from
     * whichever of the two variants carries it. Any other field (including {@code status})
     * that differs between the two variants keeps the first-seen value: the filter below then
     * sees exactly the same status it would have seen from the first-seen raw entry, so a
     * duplicate pair that disagrees on status cannot make an id appear once as included and
     * once as excluded.
     */
    private static List<Order> mergeDuplicateIds(List<Order> orders) {
        java.util.LinkedHashMap<String, Order> merged = new java.util.LinkedHashMap<>();
        int duplicates = 0;
        for (Order o : orders) {
            Order existing = merged.get(o.brokerOrderId());
            if (existing == null) {
                merged.put(o.brokerOrderId(), o);
            } else {
                duplicates++;
                merged.put(o.brokerOrderId(), mergeOrderPair(existing, o));
            }
        }
        if (duplicates > 0) {
            log.debug("ordersOpen: merged {} duplicate broker order id(s) from Saxo's mutual " +
                    "RelatedOpenOrders listing of OCO pairs", duplicates);
        }
        return new ArrayList<>(merged.values());
    }

    /** first is the first-seen variant, second the later duplicate; first's value wins ties. */
    private static Order mergeOrderPair(Order first, Order second) {
        return new Order(
                first.brokerOrderId(),
                preferNonBlank(first.clientRef(), second.clientRef()),
                first.symbol(),
                preferNonBlank(first.side(), second.side()),
                first.qty(),
                first.type(),
                first.status(),
                preferRole(first.role(), second.role()),
                preferNonNull(first.filledQty(), second.filledQty()),
                preferNonNull(first.avgFillPrice(), second.avgFillPrice()),
                preferNonNull(first.limitPrice(), second.limitPrice()),
                preferNonNull(first.stopPrice(), second.stopPrice()),
                preferNonBlank(first.parentId(), second.parentId()),
                first.submittedAt(),
                first.filledAt());
    }

    private static String preferNonBlank(String first, String second) {
        boolean firstBlank = first == null || first.isBlank();
        boolean secondBlank = second == null || second.isBlank();
        return firstBlank && !secondBlank ? second : first;
    }

    private static String preferRole(String first, String second) {
        return "other".equals(first) && second != null && !second.equals("other") ? second : first;
    }

    private static <T> T preferNonNull(T first, T second) {
        return first != null ? first : second;
    }

    /**
     * Saxo filled/closed order HISTORY via the Client-Services audit trail
     * (/cs/v1/audit/orderactivities), used when a date range is given or status ∈ {closed, all}.
     * EntryType=Last collapses each order's activity trail to its latest state (one row per
     * order). Carries real fills (AveragePrice/FilledAmount) + ActivityTime, but NO bracket-leg
     * structure — role is always "other", parentId null (leg linkage is a live-orders concept).
     * The open-orders path (/port/v1/orders/me) is unchanged and still used for every non-history call.
     * status is a router here, not a filter — see exit-tools.md
     */
    private List<Order> ordersHistory(String status, String from, String to) {
        AccountContext ctx = accountContext();
        JsonNode resp = followPagination(getJson("GET /cs/v1/audit/orderactivities",
                b -> {
                    var ub = b.path("/cs/v1/audit/orderactivities")
                            .queryParam("EntryType", "Last")
                            .queryParam("ClientKey", "{ck}")
                            .queryParam("AccountKey", "{ak}")
                            .queryParam("FieldGroups", "DisplayAndFormat")
                            .queryParam("$top", "500");
                    if (from != null && !from.isBlank()) ub = ub.queryParam("FromDateTime", "{fd}");
                    if (to != null && !to.isBlank()) ub = ub.queryParam("ToDateTime", "{td}");
                    // Bind only the template vars that were added, in order.
                    if (from != null && !from.isBlank() && to != null && !to.isBlank())
                        return ub.build(ctx.clientKey(), ctx.accountKey(), from, to);
                    if (from != null && !from.isBlank())
                        return ub.build(ctx.clientKey(), ctx.accountKey(), from);
                    if (to != null && !to.isBlank())
                        return ub.build(ctx.clientKey(), ctx.accountKey(), to);
                    return ub.build(ctx.clientKey(), ctx.accountKey());
                }));
        List<Order> out = new ArrayList<>();
        for (JsonNode n : resp.path("Data")) {
            String symbol = baseSymbol(n.path("DisplayAndFormat").path("Symbol").asString(""));
            if (symbol.isBlank()) symbol = "?";
            BigDecimal filledQty = n.path("FilledAmount").isMissingNode() || n.path("FilledAmount").isNull()
                    ? null : bd(n.path("FilledAmount"));
            BigDecimal avgFillPrice = n.path("AveragePrice").isMissingNode() || n.path("AveragePrice").isNull()
                    ? null : bd(n.path("AveragePrice"));
            out.add(new Order(
                    n.path("OrderId").asString(""),
                    textOrNull(n, "ExternalReference"),
                    symbol,
                    n.path("BuySell").asString("").toLowerCase(java.util.Locale.ROOT),
                    bd(n.path("Amount")),
                    n.path("OrderType").asString("").toLowerCase(java.util.Locale.ROOT),
                    n.path("Status").asString("").toLowerCase(java.util.Locale.ROOT),
                    "other", filledQty, avgFillPrice, null,
                    null, textOrNull(n, "ActivityTime")));
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

        ObjectNode entryFields = MAPPER.createObjectNode();
        entryFields.put("Uic", ri.uic());
        entryFields.put("AssetType", ri.assetType());
        entryFields.put("BuySell", side);
        entryFields.put("Amount", req.qty());
        entryFields.put("OrderType", limit ? "Limit" : "Market");
        if (limit) entryFields.put("OrderPrice", req.limitPrice());
        entryFields.put("ManualOrder", false);
        entryFields.put("AccountKey", ctx.accountKey());
        if (req.clientRef() != null) entryFields.put("ExternalReference", req.clientRef());
        // 🔶 Saxo semantics: a Market entry order defaults to DayOrder (Saxo rejects/normalizes
        // GoodTillCancel on Market orders — a market order that doesn't fill same-session has
        // nothing left to "keep good"). Limit entries keep the GoodTillCancel default. An
        // explicit timeInForce always wins over either default.
        entryFields.set("OrderDuration", durationNode(mapTif(req.timeInForce(), limit ? "GoodTillCancel" : "DayOrder")));

        ObjectNode stopLoss = MAPPER.createObjectNode();
        stopLoss.put("OrderType", slLimit ? "StopLimit" : "StopIfTraded");
        stopLoss.put("OrderPrice", req.stopLossStop());
        if (slLimit) stopLoss.put("StopLimitPrice", req.stopLossLimit());
        stopLoss.put("BuySell", opposite);
        stopLoss.put("Amount", req.qty());
        stopLoss.put("ManualOrder", false);
        stopLoss.put("AccountKey", ctx.accountKey());
        stopLoss.set("OrderDuration", durationNode("GoodTillCancel"));

        ObjectNode fullBody = entryFields.deepCopy();
        var children = MAPPER.createArrayNode();
        // Leg order in "Orders" is load-bearing: index 0 = take-profit IF one was requested,
        // then the stop-loss. So the stop's index shifts (1 with a TP, 0 without) — any
        // per-leg error mapping from Saxo's response must derive the index from this same
        // condition rather than assuming a fixed position.
        //
        // The take-profit is optional (since 2026-07-25). Without it an entry+stop bracket is
        // built — the same shape submitFarStopFallback produces, but deliberately instead of
        // reactively after a 400 TooFarFromEntryOrder.
        if (req.takeProfitLimit() != null) {
            ObjectNode takeProfit = MAPPER.createObjectNode();
            takeProfit.put("OrderType", "Limit");
            takeProfit.put("OrderPrice", req.takeProfitLimit());
            takeProfit.put("BuySell", opposite);
            takeProfit.put("Amount", req.qty());
            takeProfit.put("ManualOrder", false);
            takeProfit.put("AccountKey", ctx.accountKey());
            takeProfit.set("OrderDuration", durationNode("GoodTillCancel"));
            children.add(takeProfit);
        }
        children.add(stopLoss);
        fullBody.set("Orders", children);

        // X-Request-ID ist ein PRO-VERSUCH-Schlüssel, kein Geschäftsschlüssel: Saxo
        // dedupliziert darauf, und ein über Retries hinweg konstanter Wert brennt ihn nach
        // dem ersten Reject dauerhaft aus (beobachtet 2026-07-25: 400 → Retry → 409).
        // Der stabile Geschäftsschlüssel ist ExternalReference (= clientRef) im Body.
        // Der Schutz gegen Doppel-Orders bei einem Retry nach clientseitigem Timeout liegt
        // dadurch beim Aufrufer (Dracul: orderByRef-Adoption vor dem Platzieren).
        String requestId = UUID.randomUUID().toString();

        try {
            JsonNode resp = client.post().uri("/trade/v2/orders")
                    .header("Authorization", bearer())
                    .header("X-Request-ID", requestId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(fullBody)
                    .retrieve().body(JsonNode.class);
            String orderId = resp == null ? null : resp.path("OrderId").asString(null);
            return withLegIds(orderId, req.clientRef());
        } catch (RestClientResponseException e) {
            // Reactive far-stop trigger: a 400 here is the atomic reject path — Saxo either
            // accepts the whole bracket body or rejects it wholesale (this endpoint has no
            // 200-with-per-leg-errors shape, unlike the old precheck endpoint), so a
            // TooFarFromEntryOrder reject means NOTHING was placed yet. That makes the full
            // entry+standalone-stop fallback safe to run from scratch. Any other 400 reject
            // code is reported as a plain rejection, same as before; non-400 statuses (e.g.
            // 409 duplicate) are not rejects at all and go straight to writeError untouched.
            if (e.getStatusCode().value() == 400) {
                JsonNode errorBody = parseErrorBody(e);
                String code = errorBody.path("ErrorInfo").path("ErrorCode").asString(null);
                String message = errorBody.path("ErrorInfo").path("Message").asString(null);
                if (message == null) message = errorBody.path("Message").asString(null);
                if (message == null) message = rawBody(e);
                if ("TooFarFromEntryOrder".equals(code)) {
                    // Single consolidated INFO line: raw response (status/body) + parsed
                    // code/message, so this reject doesn't produce two separate log lines.
                    // Only this branch logs itself — every other 400 reject falls through to
                    // writeError below, which owns the logging for those.
                    log.info("saxo response [POST /trade/v2/orders (bracket)]: status=400 body={} — rejected [{}]: {} for {}",
                            ProviderLogRedactor.redactBody(rawBody(e)), code, message, req.symbol());
                    String leg = rejectedLeg(errorBody, req.takeProfitLimit() != null);
                    log.info("saxo far-stop: bracket rejected [{}] for {} at leg {} "
                            + "(take-profit {}, stop {}, entry {}), falling back to entry + standalone stop",
                            code, req.symbol(), leg == null ? "unknown" : leg,
                            req.takeProfitLimit(), req.stopLossStop(), req.limitPrice());
                    var reject = new BracketReject(code, message, leg, legPrice(req, leg));
                    return submitFarStopFallback(req, ri, ctx, opposite, entryFields, reject);
                }
            }
            return writeError("POST /trade/v2/orders (bracket)", e);
        } catch (Exception e) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "saxo submitBracket failed: " + e.getMessage(), e);
        }
    }

    /**
     * Rolle des Legs, das Saxo tatsächlich abgelehnt hat — oder {@code null}, wenn der
     * Body keine Per-Leg-Information trägt.
     *
     * <p>Die Zuordnung Index → Rolle folgt der Konstruktionsreihenfolge in
     * {@code submitBracket}: mit Take-Profit ist Index 0 der TP und Index 1 der Stop,
     * ohne Take-Profit ist Index 0 der Stop. Diese Kopplung ist fragil — ändert sich die
     * Reihenfolge dort, muss sie hier mitgeändert werden; die Tests pinnen beide Fälle.
     *
     * <p>{@code OrderNotPlaced} wird übersprungen: Saxo setzt das auf die übrigen Legs,
     * wenn ein anderes Leg der Anfrage abgelehnt wurde ("Order not placed as other order
     * in request was rejected"). Das ist Kollateralschaden, nicht der Grund. Genau diese
     * Verwechslung ließ Agora am 2026-07-25 den Stop beschuldigen, obwohl der
     * Take-Profit (+28 % vom Entry) abgelehnt worden war.
     */
    static String rejectedLeg(JsonNode errorBody, boolean hasTakeProfit) {
        JsonNode orders = errorBody.path("Orders");
        if (!orders.isArray()) return null;
        for (int i = 0; i < orders.size(); i++) {
            String code = orders.get(i).path("ErrorInfo").path("ErrorCode").asString("");
            if (code.isEmpty() || "OrderNotPlaced".equals(code)) continue;
            if (hasTakeProfit) return i == 0 ? "take_profit" : "stop_loss";
            return "stop_loss";
        }
        return null;
    }

    /**
     * Why the original bracket POST was rejected, carried into {@link #submitFarStopFallback}
     * so it survives a fallback that then fails for an unrelated reason.
     *
     * <p>Before this existed, a rejected bracket followed by a failing fallback reported only
     * the SECOND failure: on 2026-07-25 the caller saw "saxo rate limited (HTTP 429)" and had
     * no way to learn that the bracket had been rejected for a too-far take-profit — so it
     * retried the identical request instead of fixing the target.
     */
    record BracketReject(String code, String message, String leg, BigDecimal legPrice) {
        /** {@code bracket rejected [TooFarFromEntryOrder am take_profit 237.71]} */
        String describe() {
            StringBuilder sb = new StringBuilder("bracket rejected [");
            sb.append(code != null ? code : (message != null ? message : "unknown reason"));
            if (leg != null) {
                sb.append(" am ").append(leg);
                if (legPrice != null) sb.append(' ').append(legPrice.toPlainString());
            }
            return sb.append(']').toString();
        }

        /** Prefixes {@code detail} (the fallback's own failure) with the original cause. */
        String prefix(String detail) {
            return describe() + "; fallback dann gescheitert: " + detail;
        }
    }

    /** The price of the leg Saxo named — the number the caller has to change to get accepted. */
    private static BigDecimal legPrice(BracketOrderRequest req, String leg) {
        if ("take_profit".equals(leg)) return req.takeProfitLimit();
        if ("stop_loss".equals(leg)) return req.stopLossStop();
        return null;
    }

    /**
     * Far-stop fallback (triggered reactively when the real bracket POST rejects with
     * {@code TooFarFromEntryOrder}): Saxo's proximity band rejects a bracket whose stop-loss
     * sits outside it, so instead of a single bracket POST this places the entry alone, then
     * a standalone {@code StopIfTraded} at the
     * requested stop level — no take-profit leg (Dracul exits such positions via its own
     * trailing chandelier, so a lone entry/stop needs no TP). Two distinct {@code X-Request-ID}s
     * are used (Saxo dedupes by that header) since these are two independent order placements.
     *
     * <p><b>Fail-safe (non-negotiable):</b> once the entry is placed, this position must never
     * be left without a protective stop. If the standalone stop POST fails for any reason
     * (throws, or the response carries no usable {@code OrderId}), {@link #protectUnprotectedEntry}
     * runs a uniform best-effort cancel-then-flatten so the entry is neutralized whether it
     * ended up unfilled, partially filled, or fully filled. Either way the fallback reports
     * {@code rejected("STOP_PLACEMENT_FAILED")} rather than an accepted-but-unprotected result.
     */
    private OrderResult submitFarStopFallback(BracketOrderRequest req, SaxoInstrumentResolver.ResolvedInstrument ri,
                                                AccountContext ctx, String opposite, ObjectNode entryBody,
                                                BracketReject reject) {
        sleepBeforeFarStopFallback();
        String entryId;
        try {
            JsonNode resp = client.post().uri("/trade/v2/orders")
                    .header("Authorization", bearer())
                    .header("X-Request-ID", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(entryBody)
                    .retrieve().body(JsonNode.class);
            entryId = resp == null ? null : resp.path("OrderId").asString(null);
        } catch (RestClientResponseException e) {
            // Nothing has been placed yet — safe to report as a plain reject, same as the
            // CLEAN path's error mapping. But writeError only RETURNS for a 400; for 409 and
            // for everything readError covers (401/403/404/429/5xx) it THROWS instead. Both
            // exits must carry the original bracket reject forward, so the throwing one is
            // caught here and re-thrown with the same kind and an enriched message.
            try {
                OrderResult rejected = writeError("POST /trade/v2/orders (far-stop entry)", e);
                return OrderResult.rejected(reject.prefix(rejected.rejectReason()), rejected.rejectCode());
            } catch (BrokerException be) {
                throw new BrokerException(be.kind(), reject.prefix(be.getMessage()), be);
            }
        } catch (Exception e) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    reject.prefix("saxo submitBracket (far-stop entry) failed: " + e.getMessage()), e);
        }

        ObjectNode standaloneStop = MAPPER.createObjectNode();
        standaloneStop.put("Uic", ri.uic());
        standaloneStop.put("AssetType", ri.assetType());
        standaloneStop.put("BuySell", opposite);
        standaloneStop.put("Amount", req.qty());
        standaloneStop.put("OrderType", "StopIfTraded");
        standaloneStop.put("OrderPrice", req.stopLossStop());
        standaloneStop.put("ManualOrder", false);
        standaloneStop.put("AccountKey", ctx.accountKey());
        standaloneStop.set("OrderDuration", durationNode("GoodTillCancel"));

        String stopId = null;
        Exception stopFailure = null;
        try {
            JsonNode resp = client.post().uri("/trade/v2/orders")
                    .header("Authorization", bearer())
                    .header("X-Request-ID", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(standaloneStop)
                    .retrieve().body(JsonNode.class);
            stopId = resp == null ? null : resp.path("OrderId").asString(null);
        } catch (Exception e) {
            if (e instanceof RestClientResponseException rce) {
                log.info("saxo response [POST /trade/v2/orders (far-stop stop)]: status={} body={}",
                        rce.getStatusCode().value(), rawBody(rce));
            }
            stopFailure = e;
        }

        if (stopId == null) {
            return protectUnprotectedEntry(entryId, req.symbol(), stopFailure, reject);
        }

        log.info("saxo far-stop fallback: entry {} + standalone stop {} for {}", entryId, stopId, req.symbol());
        return OrderResult.accepted(entryId, req.clientRef(), "accepted", stopId, null);
    }

    /**
     * Mandatory fail-safe for {@link #submitFarStopFallback}: an entry must never be left
     * without a protective stop, whether it ended up unfilled, partially filled, or fully
     * filled. This runs ONE uniform path that covers all three outcomes identically rather
     * than branching on the cancel result:
     *
     * <ol>
     *   <li>Best-effort {@link #cancel(String)} of the entry, tolerating ANY
     *   {@link BrokerException} — cancel may legitimately fail if the entry already filled
     *   (fully or partially) before the cancel reached it, or on a transient transport error.
     *   Cancel is purely "remove a still-working remainder if any is left"; its outcome is
     *   never branched on.</li>
     *   <li>Always follows with a best-effort {@link #flatten(String, java.math.BigDecimal,
     *   java.math.BigDecimal)} of the full position, which is the authoritative "close
     *   whatever position resulted" step — this is what actually protects a partial fill (Saxo
     *   cancel only pulls the still-working remainder, leaving the filled part as a live,
     *   unprotected position) just as well as a full fill. A {@code NOT_FOUND} from flatten
     *   (the entry was purely unfilled and cancel removed it — no position ever existed) is
     *   tolerated. Any other flatten failure is escalated loudly via {@code log.error} since an
     *   unprotected position may now exist with nothing automated left to try.</li>
     *   <li>Always returns {@code rejected("STOP_PLACEMENT_FAILED")} — this method never lets a
     *   cancel/flatten failure propagate as a thrown {@link BrokerException}.</li>
     * </ol>
     */
    private OrderResult protectUnprotectedEntry(String entryId, String symbol, Exception stopFailure,
                                                BracketReject reject) {
        try {
            cancel(entryId);
        } catch (BrokerException e) {
            // Cancel is best-effort only — it may legitimately fail if the entry already
            // filled (fully or partially) before the cancel reached it, or on a transient
            // transport error. Either way, flatten below is the authoritative safety net.
            log.debug("saxo far-stop fail-safe: best-effort cancel of entry {} did not succeed ({}); "
                    + "falling through to flatten regardless", entryId, e.getMessage());
        }
        try {
            flatten(symbol, BigDecimal.ONE, null);
        } catch (BrokerException e) {
            if (e.kind() != BrokerException.Kind.NOT_FOUND) {
                log.error("saxo far-stop fail-safe: cancel of unprotected entry {} and best-effort "
                        + "flatten of {} both failed to leave a confirmed clean state ({}); an "
                        + "unprotected position may exist and needs manual review",
                        entryId, symbol, e.getMessage());
            }
            // NOT_FOUND: no position existed — the entry was purely unfilled and cancel
            // already removed it. Nothing left to protect.
        }
        String cause = stopFailure == null ? "no OrderId in response" : stopFailure.getMessage();
        return OrderResult.rejected(reject.prefix("standalone stop placement failed: " + cause),
                "STOP_PLACEMENT_FAILED");
    }

    /**
     * Best-effort follow-up: Saxo's placement response carries only the parent OrderId
     * (never child leg ids — unlike Alpaca), so to hand the caller SL/TP leg ids for a
     * later per-leg modify_bracket, we re-fetch /port/v1/orders/me (same lookup
     * modifyBracket already does) and read the parent's embedded RelatedOpenOrders.
     * Immediately after placement the parent+legs may not be visible yet (Saxo eventual
     * consistency), so this is a bounded retry (at most {@link #legLookupMaxAttempts}
     * attempts, {@link #legLookupDelayMillis} apart) rather than a single shot — it stops
     * as soon as leg ids are found, so the common case (legs visible immediately) costs
     * exactly one GET and no delay. If legs never appear within the window, or any attempt
     * throws, the placement itself must still be reported accepted — leg ids simply stay
     * null; a caller can look them up later via get_orders.
     */
    private OrderResult withLegIds(String orderId, String clientRef) {
        if (orderId == null) return OrderResult.accepted(orderId, clientRef, "accepted");
        for (int attempt = 1; attempt <= legLookupMaxAttempts; attempt++) {
            OrderResult found = findLegIds(orderId, clientRef);
            if (found != null) return found;
            if (attempt < legLookupMaxAttempts) {
                sleepBetweenLegLookups(attempt);
                // A caller-thread interrupt during the delay must abort the retry loop, not
                // just be swallowed into the next attempt — sleepBetweenLegLookups restores
                // the flag (Thread.currentThread().interrupt()) but a plain loop continuation
                // would ignore it entirely and keep sleeping/retrying on an interrupted thread.
                if (Thread.currentThread().isInterrupted()) break;
            }
        }
        return OrderResult.accepted(orderId, clientRef, "accepted");
    }

    /** Single lookup attempt; returns null (not accepted-without-legs) when nothing was found yet, so the caller can retry. */
    private OrderResult findLegIds(String orderId, String clientRef) {
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
                    return null;
                }
            }
        } catch (Exception ignored) {
            // best-effort only — leg lookup failing must not fail the placement itself
        }
        return null;
    }

    /**
     * Spacing seam before the far-stop fallback's entry POST, mirroring
     * {@link #sleepBetweenLegLookups}: SaxoBrokerProviderTest zeroes {@link #farStopDelayMillis}
     * so tests never actually sleep, and overrides this method where it needs to observe the wait.
     * An interrupt is restored on the thread but does not abort the fallback — an entry that is
     * about to be placed must not be skipped silently.
     */
    void sleepBeforeFarStopFallback() {
        if (farStopDelayMillis <= 0) return;
        try {
            Thread.sleep(farStopDelayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Injectable delay seam: SaxoBrokerProviderTest zeroes legLookupDelayMillis so tests never actually sleep. */
    private void sleepBetweenLegLookups(int attempt) {
        try {
            Thread.sleep(legLookupDelayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * SIM-verified (see saxo-sim-spike.md Q2/Q3): pre-fill, only the parent shows up in
     * /port/v1/orders/me (OrderRelation "IfDoneMaster"); its children are EMBEDDED in
     * RelatedOpenOrders — not separate top-level orders — so we fetch raw JsonNode (not
     * parseOrder) to reach that array. There is no MasterOrderId anywhere; post-fill the
     * parent id vanishes entirely and legs become sibling-referencing Oco orders, which is
     * out of scope for v1 (rejected LEG_NOT_FOUND — uniform with Alpaca's post-fill-not-found
     * shape — matching "filled orders' protection legs must be modified individually").
     * Each present leg is PATCHed individually with the SIM-minimal
     * body (no Uic/Amount/BuySell/ManualOrder) using the child's own OpenOrderType/Duration.
     */
    @Override
    public OrderResult modifyBracket(String id, String symbol, BigDecimal stop, BigDecimal target) {
        return modifyBracket(id, symbol, stop, target, null, null);
    }

    @Override
    public OrderResult modifyBracket(String id, String symbol, BigDecimal stop, BigDecimal target,
                                     String stopOrderId, String targetOrderId) {
        // Guard: both params null → nothing to modify
        if (stop == null && target == null) {
            return OrderResult.rejected("nothing to modify — provide stop and/or target", "NO_CHANGES");
        }

        AccountContext ctx = accountContext();
        if (stopOrderId != null || targetOrderId != null) {
            return modifyNamedLegs(ctx, id, stop, target, stopOrderId, targetOrderId);
        }
        JsonNode resp = getJson("/port/v1/orders/me");
        JsonNode parent = null;
        for (JsonNode n : resp.path("Data")) {
            if (id.equals(n.path("OrderId").asString(null))) { parent = n; break; }
        }
        JsonNode children = parent == null ? null : parent.path("RelatedOpenOrders");
        if (parent == null || !children.isArray() || children.isEmpty()) {
            OrderResult fb = modifyBySymbolFallback(ctx, symbol, stop, target, id);
            if (fb != null) return fb;
            return OrderResult.rejected("no working stop/take-profit leg for bracket " + id + " / " + symbol,
                    "LEG_NOT_FOUND");
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
     * stop/take-profit leg and corrupt its price instead of correctly falling through to a
     * rejected LEG_NOT_FOUND.
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
        if (stop != null && slLeg == null) return null;   // let caller reject LEG_NOT_FOUND uniformly
        if (target != null && tpLeg == null) return null;
        if (stop != null) { OrderResult r = patchLeg(ctx, slLeg, stop); if (!r.accepted()) return r; }
        if (target != null) { OrderResult r = patchLeg(ctx, tpLeg, target); if (!r.accepted()) return r; }
        return OrderResult.accepted(id, null, "replaced");
    }

    /**
     * Explicit leg addressing: the caller says which order carries the stop (and/or the take-profit)
     * instead of letting the parent lookup or the by-Uic fallback guess. This is the ONLY correct
     * path for a position built in more than one tranche — verified on the paper book 2026-08-04,
     * where a single symbol carried two working detached {@code StopIfTraded} orders (24 + 22
     * shares of one 46-share position) and {@link #modifyBySymbolFallback}, which keeps the LAST
     * Stop-type order it scans, would have patched one of them twice and the other never.
     *
     * <p>The named order is looked up in BOTH shapes Saxo produces, because both were live at the
     * same moment on that book: a detached top-level order once the tranche has filled, and an
     * order still EMBEDDED in an unfilled parent's {@code RelatedOpenOrders}.
     *
     * <p>Two things it refuses rather than guesses: an id that names no working order
     * ({@code LEG_NOT_FOUND} — the leg may have filled or been cancelled, and inventing a
     * substitute would move the wrong stop), and an id whose {@code OpenOrderType} is not the leg
     * type being priced ({@code LEG_TYPE_MISMATCH} — naming the ENTRY order must never re-price
     * the entry).
     */
    private OrderResult modifyNamedLegs(AccountContext ctx, String id, BigDecimal stop, BigDecimal target,
                                        String stopOrderId, String targetOrderId) {
        if (stop != null && stopOrderId == null) {
            return OrderResult.rejected(
                    "stopOrderId is required once any leg id is given — naming one leg and leaving the "
                            + "other to be guessed defeats the point", "LEG_ID_REQUIRED");
        }
        if (target != null && targetOrderId == null) {
            return OrderResult.rejected(
                    "targetOrderId is required once any leg id is given — naming one leg and leaving the "
                            + "other to be guessed defeats the point", "LEG_ID_REQUIRED");
        }

        JsonNode resp = getJson("/port/v1/orders/me");
        JsonNode slLeg = null;
        JsonNode tpLeg = null;
        if (stop != null) {
            slLeg = findOrderById(resp, stopOrderId);
            OrderResult bad = rejectUnusableLeg(slLeg, stopOrderId, "stop-loss", true);
            if (bad != null) return bad;
        }
        if (target != null) {
            tpLeg = findOrderById(resp, targetOrderId);
            OrderResult bad = rejectUnusableLeg(tpLeg, targetOrderId, "take-profit", false);
            if (bad != null) return bad;
        }

        boolean stopMoved = false;
        if (slLeg != null) {
            OrderResult r = patchLeg(ctx, slLeg, stop);
            if (!r.accepted()) return r;
            stopMoved = true;
        }
        if (tpLeg != null) {
            OrderResult r = patchLeg(ctx, tpLeg, target);
            // Partial success is reported as partial: the stop is already at its new level at the
            // broker even though this call failed overall, and a caller that re-reads its own
            // request would otherwise write the wrong state into its book.
            if (!r.accepted()) return stopMoved ? withAlreadyMovedStop(r, stop) : r;
        }
        return OrderResult.accepted(id, null, "replaced");
    }

    /** Null when the named order is usable as this leg, otherwise the rejection explaining why not. */
    private static OrderResult rejectUnusableLeg(JsonNode leg, String orderId, String legName, boolean wantStop) {
        if (leg == null) {
            return OrderResult.rejected(
                    "no working order " + orderId + " to modify as the " + legName + " leg", "LEG_NOT_FOUND");
        }
        String type = leg.path("OpenOrderType").asString("");
        boolean matches = wantStop ? type.contains("Stop") : "Limit".equals(type);
        if (!matches) {
            return OrderResult.rejected("order " + orderId + " is not a " + legName
                    + " leg (OpenOrderType=" + type + ")", "LEG_TYPE_MISMATCH");
        }
        return null;
    }

    /**
     * Finds one order by exact OrderId across both shapes {@code /port/v1/orders/me} returns: a
     * top-level order, and a leg embedded in some parent's {@code RelatedOpenOrders}. Which shape a
     * given leg is in depends only on whether its own entry has filled yet, so a lookup that
     * checked one of them would work until the day it silently didn't.
     */
    private static JsonNode findOrderById(JsonNode resp, String orderId) {
        for (JsonNode n : resp.path("Data")) {
            if (orderId.equals(n.path("OrderId").asString(null))) return n;
            for (JsonNode c : n.path("RelatedOpenOrders")) {
                if (orderId.equals(c.path("OrderId").asString(null))) return c;
            }
        }
        return null;
    }

    /** Folds "stop-loss already moved" into a rejection that followed a successful SL patch. */
    private static OrderResult withAlreadyMovedStop(OrderResult rejection, BigDecimal newStop) {
        return OrderResult.rejected(
                "take-profit update failed AFTER stop-loss was already moved to " + newStop.toPlainString()
                        + ": " + rejection.rejectReason(),
                rejection.rejectCode());
    }

    private OrderResult patchLeg(AccountContext ctx, JsonNode child, BigDecimal newPrice) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("AccountKey", ctx.accountKey());
        body.put("OrderId", child.path("OrderId").asString(""));
        // Preserve the leg's own AssetType from the fetched order rather than hardcoding
        // "Stock" — Saxo ReplaceOrder rejects a mismatched AssetType for non-equity legs
        // (e.g. options/futures brackets), and "Stock" is only the right default absent info.
        body.put("AssetType", child.path("AssetType").asString("Stock"));
        body.put("OrderType", child.path("OpenOrderType").asString(""));
        body.put("OrderPrice", newPrice);
        body.set("OrderDuration", durationNode(child.path("Duration")));
        try {
            JsonNode resp = client.patch().uri("/trade/v2/orders")
                    .header("Authorization", bearer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve().body(JsonNode.class);
            return OrderResult.accepted(null, null, "replaced");
        } catch (RestClientResponseException e) {
            return writeError("PATCH /trade/v2/orders (leg)", e);
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
     *
     * <p><b>H6 — 🔶 Saxo semantics:</b> unlike Alpaca (whose bracket auto-cancels its
     * sibling leg on OCO), Saxo does NOT auto-cancel a bracket's detached SL/TP legs when
     * the position is closed independently (e.g. via this flatten, or a manual close in the
     * Saxo UI). A stop left working after flatten can later execute against a since-reversed
     * or absent position → an unintended reverse position with no protection. So before
     * placing the closing Market order, this looks up open orders sharing the position's Uic
     * and cancels any protective (Stop-type or Limit-type) legs it finds — cancel-first, so
     * a stop can't fire mid-close. If that lookup itself fails, the close still proceeds (Saxo
     * requires an explicit, deliberate flatten to go through even degraded), but the
     * accepted result's status carries a visible warning so the caller knows the legs were
     * not verified/canceled and may need manual cleanup.
     *
     * <p><b>S7a — partial close restores the legs:</b> when {@code remainingQty > 0}, the
     * cancelled protective legs are not simply dropped — they are re-placed sized to the
     * remainder ({@link LegAllocation#allocate}) BEFORE the closing Market order goes out, so a
     * restore failure costs nothing (only cancels have happened yet) rather than leaving a
     * freshly-trimmed position unprotected. A leg whose price/id/amount couldn't be read
     * ({@link ProtectiveLeg#from}) is left uncancelled and ineligible for restoration in the
     * first place — cancelling it would create a slice with nothing to put back. It is still
     * real, working protection under its original id, so it is reported in {@code
     * protectiveLegs()} with a null qty/price (there is nothing parsed to report) exactly like a
     * failed-cancel leg — never silently dropped. Only legs that
     * WERE cancelled are eligible for restoration: if a cancel itself failed, the surviving old leg plus
     * freshly placed new ones would work against a smaller holding than either alone expects —
     * an unintended reverse position the moment one triggers. Any such incompleteness puts the
     * legs that DID cancel cleanly back at their FULL original size and rejects the trim with
     * LEG_CANCEL_INCOMPLETE (every cancelled leg restored) or LEG_RESTORE_FAILED_UNPROTECTED
     * (one of those restores itself failed) rather than leaving a smaller, partially-protected
     * mix — a DIFFERENT failure mode from the sized-leg placement step below, which rejects with
     * LEG_RESTORE_FAILED instead. If placing the sized legs partially succeeds
     * (some already live at the broker) before one fails, the already-placed ones are cancelled
     * BEFORE the full-size rollback — otherwise the rolled-back legs plus these orphans would
     * double the opposite-side interest against the holding. If cancelling an orphan itself
     * fails, it becomes live, unaccounted-for protection and is reported in {@code
     * protectiveLegs()} (code LEG_RESTORE_FAILED_UNPROTECTED) so the caller can reconcile it
     * rather than lose track of it. A cancelled leg that rounds to 0 shares under
     * {@link LegAllocation} is similarly never silently dropped — it is called out in the
     * accepted result's status.
     * <b>Known limitation (spec §4.1, closed by S7c, not here):</b> between the closing order
     * being accepted and it actually filling, the holding briefly exceeds what the restored
     * stops cover — the position is still full-sized while the new legs already assume the
     * post-close remainder.
     *
     * <p><b>M-T6 — idempotent retry:</b> the same lookup also counts any already-working
     * opposite-side Market order on this Uic (a prior flatten call whose HTTP response was
     * lost to the caller, e.g. on a timeout, but which the broker accepted). Protective
     * Stop/Limit legs are excluded from this count — they are not a "close" and are exactly
     * what H6 cancels above, so counting them would make every flatten look
     * already-in-flight and needlessly block it. The requested close size (whether derived
     * from the full position, a fraction, or an explicit qty) is the target TOTAL amount to
     * close; any already-pending opposite-side Market quantity counts toward that target and
     * is subtracted from it before placing this call's order — placing the full target on top
     * of a smaller pending close would stack more sell (or buy) interest than the position
     * actually holds. If the pending quantity already covers the requested close (≥ target),
     * this returns rejected("close already pending", ...) instead of placing a second order.
     * If the lookup fails, this check is skipped (the pending quantity is treated as zero, so
     * the full target is placed) rather than blocking a legitimate close during a transient
     * outage.
     */
    @Override
    public OrderResult flatten(String symbol, BigDecimal fraction, BigDecimal qty) {
        if (fraction != null && (fraction.signum() <= 0 || fraction.compareTo(BigDecimal.ONE) > 0)) {
            return OrderResult.rejected(
                    "fraction must be in (0,1]: " + fraction.toPlainString(), "INVALID_FRACTION");
        }
        SaxoInstrumentResolver.ResolvedInstrument ri;
        try {
            ri = resolver.resolve(symbol);
        } catch (SaxoInstrumentResolver.SymbolResolutionException e) {
            return OrderResult.rejected(e.getMessage(), "SYMBOL");
        }
        AccountContext ctx = accountContext();
        NetPositionSnapshot pos = resolveNetPosition(symbol, ri, ctx, "flatten");
        BigDecimal available = pos.available();
        String opposite = pos.opposite();

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

        RelatedOrdersLookup related = lookupRelatedOrders(ri.uic());

        BigDecimal pendingOppositeCloseQty = BigDecimal.ZERO;
        for (JsonNode n : related.orders()) {
            String type = n.path("OpenOrderType").asString("");
            if ("Market".equalsIgnoreCase(type) && opposite.equalsIgnoreCase(n.path("BuySell").asString(""))) {
                pendingOppositeCloseQty = pendingOppositeCloseQty.add(bd(n.path("Amount")));
            }
        }
        // M-T6: closeQty is the target TOTAL to close; any already-pending opposite-side
        // Market quantity counts toward that target, so only the remainder is placed here.
        // Placing the full closeQty on top of a smaller pending close would stack more
        // sell/buy interest than the position holds (oversell / unintended short once both
        // fill) — see finding M-T6 follow-up.
        BigDecimal effectiveCloseQty = closeQty.subtract(pendingOppositeCloseQty);
        if (effectiveCloseQty.signum() <= 0) {
            return OrderResult.rejected(
                    "a close of >= the requested size is already working", "CLOSE_ALREADY_PENDING");
        }

        // H6: only cancel protective legs on the OPPOSITE side of the position (a genuine
        // SL/TP for this position is always opposite-side). Same-side Stop/Limit orders are
        // unrelated (e.g. a resting add-to-position limit, or a stop-entry for a new position
        // on the same instrument) and must be left alone.
        BigDecimal remainingQty = available.subtract(closeQty);
        List<ProtectiveLeg> cancelled = new ArrayList<>();
        // T5 rollback finding: a leg whose CANCEL call itself failed is left LIVE at the broker
        // under its original id — that id never entered `cancelled` (only successfully cancelled
        // legs do) and so never reached the rollback's restored-legs list either, even though it
        // is real, working protection. Tracked separately here so every rejected result below can
        // still name it.
        List<RestoredLeg> uncancelledLive = new ArrayList<>();
        boolean cancelIncomplete = false;
        for (JsonNode n : related.orders()) {
            String type = n.path("OpenOrderType").asString("");
            boolean protectiveType = type.contains("Stop") || "Limit".equalsIgnoreCase(type);
            boolean oppositeSide = opposite.equalsIgnoreCase(n.path("BuySell").asString(""));
            if (!protectiveType || !oppositeSide) continue;
            String legOrderId = n.path("OrderId").asString(null);
            if (legOrderId == null) continue;

            // ProtectiveLeg.from is pure (no I/O) — read it BEFORE deciding whether to cancel.
            // On a partial close, a leg that can't be read back can never be restored, so
            // cancelling it would leave the position naked for that slice with nothing to put
            // back (rule 2). Leave it working and reject the trim instead. A full flatten
            // (remainingQty == 0) restores nothing regardless, so it always cancels — matching
            // today's H6 behaviour byte for byte (rule 3).
            Optional<ProtectiveLeg> leg = ProtectiveLeg.from(n);
            if (remainingQty.signum() > 0 && leg.isEmpty()) {
                cancelIncomplete = true;
                // Left uncancelled on purpose (see the javadoc above), but it is still real,
                // working protection under legOrderId — report it exactly like a failed-cancel
                // leg (rule below) so the caller doesn't forget an order that is still live at
                // the broker. ProtectiveLeg.from() failed, so there's no parsed qty/price to
                // report; null is honest here, a fabricated value would not be.
                uncancelledLive.add(new RestoredLeg(legOrderId, legOrderId, null, null));
                continue;
            }

            try {
                cancel(legOrderId);
            } catch (Exception e) {
                cancelIncomplete = true;
                related = related.withError("failed to cancel related order " + legOrderId
                        + ": " + e.getMessage());
                // The cancel failed, so the leg is still live at the broker under its ORIGINAL
                // id — never place a "new" order for it, just report the id that is already
                // working (see the uncancelledLive javadoc note above).
                leg.ifPresent(l -> uncancelledLive.add(new RestoredLeg(l.orderId(), l.orderId(), l.amount(), l.price())));
                continue;
            }

            if (leg.isPresent()) cancelled.add(leg.get());
        }

        // S7a: a partial close (remainingQty > 0) must put the protective legs back sized to
        // the remainder BEFORE placing the closing order — a failure here costs nothing (no
        // broker state has changed beyond the cancels above), whereas a failure discovered
        // after the close would leave a filled position with no protection at all. Only legs
        // that were actually cancelled above are eligible: if one cancel failed (or a leg
        // wasn't reconstructible), the surviving old leg plus freshly placed new ones would
        // work against a smaller holding than either alone expects — an unintended reverse
        // position the moment one triggers. So any incompleteness here puts the cancelled legs
        // back at FULL size and rejects the trim rather than risk that.
        if (remainingQty.signum() > 0 && cancelIncomplete) {
            List<RestoredLeg> back = placeLegsAtFullSize(ctx, ri, cancelled);
            List<RestoredLeg> allKnown = new ArrayList<>(back);
            allKnown.addAll(uncancelledLive);
            // Deliberately NOT legRestoreFailureCode here (even though this is also a per-leg
            // under/over-protected classification): that helper merges live quantities with
            // HashMap.merge(key, qty, BigDecimal::add), and `uncancelledLive` entries carry a
            // null qty (ProtectiveLeg.from failed, so there is nothing parsed to report) —
            // Map.merge throws NPE on a null value. This simpler two-way ternary is the only
            // classification this branch can safely use.
            return OrderResult.rejectedWithLegs(
                    "could not cancel every protective leg cleanly; the close was not placed",
                    back.size() == cancelled.size() ? "LEG_CANCEL_INCOMPLETE"
                                                    : "LEG_RESTORE_FAILED_UNPROTECTED",
                    allKnown, false);
        }

        List<RestoredLeg> restored = List.of();
        boolean legsCollapsed = false;
        String allocWarning = null;
        if (remainingQty.signum() > 0) {
            LegAllocation.Result alloc = LegAllocation.allocate(cancelled, remainingQty, available);
            if (alloc.warning() != null) log.warn("flatten {}: {}", symbol, alloc.warning());
            restored = placeSizedLegs(ctx, ri, alloc.sized());
            if (restored.size() != alloc.sized().size()) {
                // Some sized legs are ALREADY live at the broker (placeSizedLegs stopped at the
                // first failure but didn't undo the ones before it). Roll them back the same
                // interleaved way the determinate-close-failure branch below does (fix round 3:
                // this branch used to cancel every orphan first and place every full-size leg
                // after, unconditionally — the same "sustained failure strips protection to
                // zero" defect that branch was fixed for).
                InterleavedRollback rb = interleaveRollback(ctx, ri, cancelled, restored);
                return OrderResult.rejectedWithLegs(
                        "could not restore the protective legs for the remainder; the close was not placed",
                        rb.fullyAccounted() ? "LEG_RESTORE_FAILED" : LEG_RESTORE_FAILURE_CODE,
                        rb.allKnown(), false);
            }
            legsCollapsed = alloc.collapsed();
            allocWarning = alloc.warning();

            // A cancelled leg can size to 0 shares under rounding (e.g. a Limit leg at
            // amount*remaining/available truncating to 0) and LegAllocation silently drops it
            // — restored.size() == alloc.sized().size() stays true above, so that check alone
            // never catches this. Protection disappearing without a signal is exactly the
            // failure class this change exists to eliminate, so surface it in the status.
            java.util.Set<String> restoredIds = alloc.sized().stream()
                    .map(s -> s.leg().orderId()).collect(java.util.stream.Collectors.toSet());
            long droppedCount = cancelled.stream().filter(l -> !restoredIds.contains(l.orderId())).count();
            if (droppedCount > 0) {
                String droppedWarning = droppedCount + " cancelled leg(s) rounded to 0 shares "
                        + "and were not restored";
                allocWarning = allocWarning == null ? droppedWarning : allocWarning + "; " + droppedWarning;
            }
        }

        ObjectNode body = MAPPER.createObjectNode();
        body.put("Uic", ri.uic());
        body.put("AssetType", ri.assetType());
        body.put("BuySell", opposite);
        body.put("Amount", effectiveCloseQty);
        body.put("OrderType", "Market");
        body.put("ManualOrder", false);
        body.put("AccountKey", ctx.accountKey());
        body.set("OrderDuration", durationNode("DayOrder"));

        try {
            JsonNode resp2 = client.post().uri("/trade/v2/orders")
                    .header("Authorization", bearer())
                    .header("X-Request-ID", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve().body(JsonNode.class);
            String orderId = resp2 == null ? null : resp2.path("OrderId").asString(null);
            String status = related.error() == null ? "accepted"
                    : "accepted (warning: " + related.error() + ")";
            if (allocWarning != null) status = status + " (warning: " + allocWarning + ")";
            return OrderResult.acceptedWithLegs(orderId, null, status, effectiveCloseQty, remainingQty, null,
                    restored, legsCollapsed);
        } catch (RestClientResponseException e) {
            if (remainingQty.signum() <= 0) {
                // Full flatten: nothing was restored above (remainingQty == 0 skips the whole
                // restore block), so there is nothing to roll back either — unchanged behaviour,
                // byte for byte, matching today's plain reject/throw via writeError.
                return writeError("POST /trade/v2/orders (flatten)", e);
            }
            if (isDeterminateWriteFailure(e)) {
                // The broker's synchronous response says the close was NOT placed — safe to
                // grow the sized-down legs back to their full original size. But `restored`
                // (the sized legs from the block above) are ALREADY live at the broker: placing
                // full-size legs on top of them without cancelling first would double the
                // opposite-side interest working against the holding — the same hazard the
                // placeSizedLegs-failure branch above already guards against, arriving here from
                // the other direction (the sized legs succeeded, but the CLOSE then failed).
                //
                // Fix round 2 finding: cancelling every sized leg FIRST and only THEN placing
                // every full-size leg (mirroring the placeSizedLegs-failure branch's shape
                // literally) has a gap: a failure between the two phases — e.g. a rate limit
                // that hasn't lifted, so the full-size restore POSTs 429 right after the sized
                // legs were just cancelled for the same reason — cancels ALL protection before
                // placing ANY of it back, leaving the position naked. Measured: 46 held, both
                // full-size POSTs 429, journal showed R12/R11 cancelled and nothing placed —
                // zero working stops on the single most routine failure status. Fixed via
                // interleaveRollback (shared with the placeSizedLegs-failure branch above).
                InterleavedRollback rb = interleaveRollback(ctx, ri, cancelled, restored);
                OrderResult closeReject = safeWriteError("POST /trade/v2/orders (flatten)", e);
                if (!rb.fullyAccounted()) {
                    String code = LEG_RESTORE_FAILURE_CODE;
                    log.error("saxo flatten {}: close rejected ({}) AND protective legs could not be "
                            + "fully restored — position uic={} holds {} shares with {} full-size "
                            + "leg(s) restored, {} sized leg(s) uncancelled orphan(s), {} sized "
                            + "leg(s) never reached, out of {} originally cancelled",
                            symbol, closeReject.rejectReason(), ri.uic(), available.toPlainString(),
                            rb.back().size(), rb.orphans().size(), rb.untouched().size(), cancelled.size());
                    return OrderResult.rejectedWithLegs(closeReject.rejectReason(), code, rb.allKnown(), false);
                }
                return OrderResult.rejectedWithLegs(closeReject.rejectReason(),
                        closeReject.rejectCode(), rb.allKnown(), false);
            }
            // Indeterminate: the broker may already have accepted the close (409 duplicate
            // X-Request-ID replay, 5xx, or any other status readError maps to UNAVAILABLE) and
            // the synchronous response merely failed to communicate it — the exact lost-response
            // case M-T6 (see this method's class javadoc) exists to reconcile on retry. Restoring
            // full-size protection now, on top of a close that actually filled, would leave MORE
            // opposite-side interest working than the position holds: a naked reverse position
            // the moment it triggers. So the legs stay exactly as sized to the remainder and this
            // escalates instead of silently rolling back.
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "saxo flatten close outcome unknown; protective legs remain sized to the "
                            + "remainder: " + e.getMessage(), e);
        } catch (Exception e) {
            if (remainingQty.signum() <= 0) {
                throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                        "saxo flatten failed: " + e.getMessage(), e);
            }
            // Transport-level failure (timeout, connection reset, …): same indeterminate
            // reasoning as the RestClientResponseException branch above — the request may have
            // reached Saxo even though the response never came back, so the legs stay sized to
            // the remainder rather than being grown back on a guess.
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "saxo flatten failed after leg restore; protective legs remain sized to the "
                            + "remainder: " + e.getMessage(), e);
        }
    }

    /**
     * Standalone protective stop — see {@link BrokerProvider#placeProtectiveStop}. Additive by
     * construction: it reads the net position to derive the opposite side (the same derivation
     * {@link #flatten} uses — {@code amount.signum() > 0 ? "Sell" : "Buy"}), then POSTs exactly
     * ONE {@code StopIfTraded} order for {@code qty} at {@code stopPrice} with {@code
     * GoodTillCancel} duration — the same standalone-stop body shape as the far-stop fallback's
     * {@code standaloneStop} (see {@link #submitFarStopFallback}). It cancels nothing and reads
     * no other order.
     *
     * <p>There is no rollback here because there is nothing that was changed to roll back — but
     * that only holds for a DETERMINATE POST failure (400/401/403/404/429, mirroring {@link
     * #isDeterminateWriteFailure}): those statuses mean Saxo did not place the order, so the
     * position is exactly as it was and a plain rejection is accurate. A 409 (duplicate
     * X-Request-ID replay) or 5xx is INDETERMINATE — the stop may already be live at the broker
     * and only the response was lost — so that case throws rather than reporting a definite
     * rejection, exactly like {@link #flatten}'s close-POST handling. Reporting an indeterminate
     * failure as "not placed" is the one mistake this tool cannot afford: a caller that retries
     * on a false rejection can double the protective interest against the position.
     *
     * <p>The caller is responsible for not double-covering shares another working stop already
     * protects; this method has no visibility into other orders by design.
     */
    @Override
    public OrderResult placeProtectiveStop(String symbol, BigDecimal qty, BigDecimal stopPrice) {
        if (qty == null || qty.signum() <= 0) {
            return OrderResult.rejected(
                    "qty must be positive: " + (qty == null ? "null" : qty.toPlainString()), "INVALID_QTY");
        }
        SaxoInstrumentResolver.ResolvedInstrument ri;
        try {
            ri = resolver.resolve(symbol);
        } catch (SaxoInstrumentResolver.SymbolResolutionException e) {
            return OrderResult.rejected(e.getMessage(), "SYMBOL");
        }
        AccountContext ctx = accountContext();
        NetPositionSnapshot pos = resolveNetPosition(symbol, ri, ctx, "placeProtectiveStop");
        BigDecimal available = pos.available();
        String opposite = pos.opposite();

        if (qty.compareTo(available) > 0) {
            return OrderResult.rejected(
                    "requested qty " + qty.toPlainString() + " exceeds position " + available.toPlainString(),
                    "QTY_EXCEEDS_POSITION");
        }

        ObjectNode body = MAPPER.createObjectNode();
        body.put("Uic", ri.uic());
        body.put("AssetType", ri.assetType());
        body.put("BuySell", opposite);
        body.put("Amount", qty);
        body.put("OrderType", "StopIfTraded");
        body.put("OrderPrice", stopPrice);
        body.put("ManualOrder", false);
        body.put("AccountKey", ctx.accountKey());
        body.set("OrderDuration", durationNode("GoodTillCancel"));

        try {
            JsonNode resp2 = client.post().uri("/trade/v2/orders")
                    .header("Authorization", bearer())
                    .header("X-Request-ID", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve().body(JsonNode.class);
            String orderId = resp2 == null ? null : resp2.path("OrderId").asString(null);
            return OrderResult.accepted(orderId, null, "accepted");
        } catch (RestClientResponseException e) {
            if (isDeterminateWriteFailure(e)) {
                // Determinate: Saxo did not place the order (parsed 400 reject, or the request
                // never reached order entry at all) — safe to report a plain rejection.
                return safeWriteError("POST /trade/v2/orders (placeProtectiveStop)", e);
            }
            // Indeterminate (409 duplicate-request replay, 5xx, or any other status readError
            // maps to UNAVAILABLE): the broker may already have accepted the stop and only the
            // response failed to communicate it. Reporting this as a definite rejection would
            // invite the caller to retry and double the protective interest against the
            // position — so this throws instead of returning rejected(), same reasoning as
            // flatten's close-POST handling.
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "saxo placeProtectiveStop outcome unknown for " + symbol + "; the stop may "
                            + "already have been placed — reconcile via get_orders before "
                            + "retrying: " + e.getMessage(), e);
        } catch (Exception e) {
            // Transport-level failure (timeout, connection reset, …): same indeterminate
            // reasoning as the RestClientResponseException branch above — the request may have
            // reached Saxo even though the response never came back.
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "saxo placeProtectiveStop failed; the stop may already have been placed — "
                            + "reconcile via get_orders before retrying: " + e.getMessage(), e);
        }
    }

    /**
     * Reads the current net position for {@code ri} and derives the signed size and the
     * opposite side — shared by {@link #flatten} and {@link #placeProtectiveStop}, both of
     * which need exactly this: how many shares are held, and which side a closing/protective
     * order must trade on ({@code amount.signum() > 0 ? "Sell" : "Buy"}). Throws {@code
     * BrokerException(NOT_FOUND)} when there is no open (non-zero) position for the instrument.
     */
    private NetPositionSnapshot resolveNetPosition(String symbol, SaxoInstrumentResolver.ResolvedInstrument ri,
                                                    AccountContext ctx, String logLabel) {
        JsonNode resp = followPagination(getJson("GET /port/v1/netpositions (" + logLabel + ")",
                b -> b.path("/port/v1/netpositions")
                        .queryParam("ClientKey", "{ck}")
                        .queryParam("AccountKey", "{ak}")
                        .queryParam("FieldGroups", "{fg}")
                        .build(ctx.clientKey(), ctx.accountKey(),
                                "NetPositionBase,NetPositionView,DisplayAndFormat")));

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
        return new NetPositionSnapshot(amount.abs(), amount.signum() > 0 ? "Sell" : "Buy");
    }

    /** {@code available} is always non-negative; {@code opposite} is {@code "Sell"}/{@code "Buy"}. */
    private record NetPositionSnapshot(BigDecimal available, String opposite) {}

    /**
     * True when the closing POST failed in a way that means Saxo did NOT place the order —
     * safe to restore the protective legs to full size. Mirrors the determinate statuses
     * {@link #writeError} (400 → parsed reject) and {@link #readError} (404/401/403/429) map
     * synchronously, before the order ever reaches the book: 400 is a parsed rejection, 401/403
     * is an auth failure that never got as far as order entry, 404 means there was nothing to
     * place against, and 429 means the request was throttled, not executed. Everything else —
     * 409 duplicate-request replay, 5xx, or no response at all — is indeterminate: the broker may
     * have accepted the order and only the response was lost (see the caller for why that matters).
     */
    private static boolean isDeterminateWriteFailure(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        return status == 400 || status == 401 || status == 403 || status == 404 || status == 429;
    }

    /**
     * {@link #writeError} returns an {@code OrderResult.rejected} for HTTP 400 but THROWS a
     * {@link BrokerException} for every other status it maps — 404/401/403/429 included, since
     * those are normally reached via reads ({@link #readError} through {@link #exchange}), not
     * writes. The rollback path needs a reason/code for ALL of its determinate statuses, so this
     * converts that thrown exception back into a rejected result instead of letting it propagate:
     * the close is being reported as rejected either way, only the code/message differ by status.
     */
    private static OrderResult safeWriteError(String endpoint, RestClientResponseException e) {
        try {
            return writeError(endpoint, e);
        } catch (BrokerException be) {
            String code = switch (be.kind()) {
                case NOT_FOUND -> "NOT_FOUND";
                case NOT_READY -> "RATE_LIMITED";
                case UNAVAILABLE -> "UNAVAILABLE";
            };
            return OrderResult.rejected(be.getMessage(), code);
        }
    }

    /**
     * Places one protective order; returns null when the broker did not hand back an order id.
     *
     * <p>Fix round 3 finding: this used to have a sibling {@code placeLegWithRetry} that retried
     * once on HTTP 429 before giving up. Removed — measured with it in place: Apache
     * HttpClient5's own {@code HttpRequestRetryExec} already auto-retries a 429 POST at the
     * transport layer (production behaviour, not a test-harness artifact), so the extra
     * provider-level retry only doubled the requests actually sent (up to 4 for a sustained
     * 429: 2 transport attempts × 2 provider attempts) while adding zero benefit. Worse, it was
     * unsafe: {@code placeLeg} generates a fresh {@code X-Request-ID} per call, so a
     * provider-level retry after Saxo had actually queued the order (the exact "429 after
     * accept" case the transport's OWN retry replays under one shared request id, matching the
     * 409-duplicate safety net elsewhere in this class) would place a SECOND full-size leg — a
     * real over-commit. And the sleep before retrying was unbounded ({@code Retry-After} parsed
     * with no ceiling — measured: a 3600s header would block the calling thread for the better
     * part of an hour) at exactly the moment the leg it was about to restore was at its minimum
     * protection. The interleave ({@link #flatten}'s rollback loop) is the fix; the transport
     * already covers the transient case, so this stays a single attempt.
     */
    private RestoredLeg placeLeg(AccountContext ctx, SaxoInstrumentResolver.ResolvedInstrument ri,
                                 ProtectiveLeg leg, BigDecimal qty) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("Uic", ri.uic());
        body.put("AssetType", leg.assetType());
        body.put("BuySell", leg.buySell());
        body.put("Amount", qty);
        body.put("OrderType", leg.openOrderType());
        body.put("OrderPrice", leg.price());
        if (leg.stopLimitPrice() != null) body.put("StopLimitPrice", leg.stopLimitPrice());
        body.put("ManualOrder", false);
        body.put("AccountKey", ctx.accountKey());
        body.set("OrderDuration", durationNode(leg.duration()));
        try {
            JsonNode resp = client.post().uri("/trade/v2/orders")
                    .header("Authorization", bearer())
                    .header("X-Request-ID", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve().body(JsonNode.class);
            String id = resp == null ? null : resp.path("OrderId").asString(null);
            return id == null ? null : new RestoredLeg(leg.orderId(), id, qty, leg.price());
        } catch (Exception e) {
            log.warn("saxo restore of protective leg {} ({} @ {}) failed: {}",
                    leg.orderId(), qty.toPlainString(), leg.price().toPlainString(), e.getMessage());
            return null;
        }
    }

    /**
     * Result of {@link #interleaveRollback}: the legs successfully placed at full size, the
     * sized legs whose cancel itself failed (still live, "orphans"), the sized legs never even
     * reached because the loop stopped earlier ("untouched", still live), the union of all
     * three ({@code allKnown} — everything the caller must report), and whether every original
     * leg ended up fully, cleanly restored.
     */
    private record InterleavedRollback(List<RestoredLeg> back, List<RestoredLeg> orphans,
                                       List<RestoredLeg> untouched, List<RestoredLeg> allKnown,
                                       boolean fullyAccounted) {}

    /**
     * Rolls the sized-down legs in {@code liveSized} back to their full original size,
     * interleaved PER LEG: cancel leg {@code i}'s sized replacement, then immediately place leg
     * {@code i} at full size, before touching leg {@code i+1} — rather than cancelling every
     * sized leg first and placing every full-size leg after. Shared by both of {@link #flatten}'s
     * rollback branches (the determinate-close-failure catch, and the placeSizedLegs-partial-
     * failure branch above it) — fix round 3 finding: leaving one branch interleaved and its
     * twin still cancel-all-then-place-all would let the exact same "sustained failure strips
     * protection to zero" defect survive in the other branch.
     *
     * <p>A failure at ANY step (cancel or place) stops the loop immediately and reports exactly
     * what is live: every intermediate total therefore stays at or below the position size (no
     * leg is ever represented twice), and a failure at any point still leaves whatever was live
     * before that step — the not-yet-reached legs keep their smaller, non-zero sized protection
     * rather than the whole position going unprotected at once.
     */
    private InterleavedRollback interleaveRollback(AccountContext ctx, SaxoInstrumentResolver.ResolvedInstrument ri,
                                                   List<ProtectiveLeg> originals, List<RestoredLeg> liveSized) {
        List<RestoredLeg> back = new ArrayList<>();
        List<RestoredLeg> orphans = new ArrayList<>();
        java.util.Set<String> touchedSizedIds = new java.util.HashSet<>();
        for (ProtectiveLeg original : originals) {
            RestoredLeg sized = liveSized.stream()
                    .filter(r -> r.replaces().equals(original.orderId()))
                    .findFirst().orElse(null);
            if (sized != null) {
                touchedSizedIds.add(sized.orderId());
                try {
                    cancel(sized.orderId());
                } catch (Exception cancelFailure) {
                    // The sized leg could not be cancelled — it is still live. Do NOT place the
                    // full-size replacement on top of it (that would double this leg's
                    // interest); stop here and report what is live.
                    orphans.add(sized);
                    break;
                }
            }
            RestoredLeg full = placeLeg(ctx, ri, original, original.amount());
            if (full == null) {
                // The sized leg (if any) is already cancelled and gone; the full-size
                // replacement failed to place — this leg has NO working protection right now.
                // Stop here rather than press on and risk compounding the failure.
                break;
            }
            back.add(full);
        }
        // Legs never reached because the loop stopped early keep their (uncancelled) sized
        // protection — they are still live and must be named just like the orphan whose cancel
        // itself failed.
        List<RestoredLeg> untouched = liveSized.stream()
                .filter(r -> !touchedSizedIds.contains(r.orderId()))
                .toList();
        List<RestoredLeg> allKnown = new ArrayList<>(back);
        allKnown.addAll(orphans);
        allKnown.addAll(untouched);
        boolean fullyAccounted = orphans.isEmpty() && untouched.isEmpty() && back.size() == originals.size();
        return new InterleavedRollback(back, orphans, untouched, allKnown, fullyAccounted);
    }

    /**
     * Classifies a leg-restore reject PER ORIGINAL LEG, comparing what is currently live for
     * that leg against ITS OWN original {@code amount()} — never a single cross-leg sum. Fix
     * round 3 finding: a bracket's stop-loss AND take-profit are each placed at the FULL
     * position qty ({@link #submitBracket}), both opposite-side, both matched by flatten's own
     * protective-leg filter — so a healthy bracketed position always carries 2x the holding in
     * protective interest by design, and a cross-leg sum-vs-`available` comparison called that
     * OVERCOMMITTED on every ordinary bracket. Measured: 46 held, sl-46 + tp-46, close rejected,
     * the take-profit's sized replacement failed to cancel — actual live state
     * back-sl(46)+orphan-tp(23), i.e. the take-profit is stuck UNDER its original 46, not over
     * anything; the sum-based classifier still said OVERCOMMITTED because 46+23=69 > 46 held,
     * whose documented remediation ("cancel the excess") would have cancelled the one leg that
     * actually IS fully protecting.
     *
     * <p>A leg with less live quantity than its original (including zero, i.e. missing
     * entirely) is under-protected. An "over-committed" reject code (more live than original) is
     * not modelled — it is provably unreachable through this rollback code: {@code
     * interleaveRollback} contributes at most one entry per original leg to its result (a
     * full-size {@code back} entry equals the original exactly; an orphan or untouched entry is
     * the sized, monotonically-shrunk replacement, always <= the original; and {@code
     * touchedSizedIds} prevents the same sized leg from ever appearing in more than one of
     * back/orphans/untouched), so a single original's live quantity can never exceed its own
     * amount here. Both rollback-incompleteness call sites below therefore always report this
     * one code rather than switching on a case that cannot occur.
     */
    private static final String LEG_RESTORE_FAILURE_CODE = "LEG_RESTORE_FAILED_UNPROTECTED";

    /** Places the sized legs in order; stops at the first placement failure so the caller can roll back. */
    private List<RestoredLeg> placeSizedLegs(AccountContext ctx, SaxoInstrumentResolver.ResolvedInstrument ri,
                                             List<LegAllocation.Sized> sized) {
        List<RestoredLeg> out = new ArrayList<>();
        for (LegAllocation.Sized s : sized) {
            RestoredLeg r = placeLeg(ctx, ri, s.leg(), s.qty());
            if (r == null) return out;   // caller rolls back
            out.add(r);
        }
        return out;
    }

    /** Puts every given leg back at its original (pre-cancel) size — the rollback path. */
    private List<RestoredLeg> placeLegsAtFullSize(AccountContext ctx, SaxoInstrumentResolver.ResolvedInstrument ri,
                                                  List<ProtectiveLeg> legs) {
        List<RestoredLeg> out = new ArrayList<>();
        for (ProtectiveLeg l : legs) {
            RestoredLeg r = placeLeg(ctx, ri, l, l.amount());
            if (r != null) out.add(r);
        }
        return out;
    }

    /** Open orders sharing a position's Uic, plus an optional lookup-failure message (H6/M-T6). */
    private record RelatedOrdersLookup(List<JsonNode> orders, String error) {
        RelatedOrdersLookup withError(String newError) { return new RelatedOrdersLookup(orders, newError); }
    }

    /**
     * Single lookup shared by H6 (cancel protective legs) and M-T6 (idempotent pending-close
     * check) so flatten costs at most one extra GET, not two. A lookup failure yields an
     * empty order list plus a message: H6 treats that as "proceed with a warning", M-T6
     * treats it as "can't verify, don't block the close".
     */
    private RelatedOrdersLookup lookupRelatedOrders(long uic) {
        try {
            JsonNode resp = followPagination(getJson("/port/v1/orders/me"));
            List<JsonNode> matches = new ArrayList<>();
            for (JsonNode n : resp.path("Data")) {
                if (n.path("Uic").asLong(-1) == uic) matches.add(n);
            }
            return new RelatedOrdersLookup(matches, null);
        } catch (Exception e) {
            return new RelatedOrdersLookup(List.of(),
                    "could not verify/cancel related protective orders: " + e.getMessage());
        }
    }

    @Override
    public OrderResult cancel(String brokerOrderId) {
        AccountContext ctx = accountContext();
        try {
            // See account()/positions() re: TEMPLATE_AND_VALUES encoding — AccountKey is
            // bound as a build(Object...) template variable, never concatenated/URLEncoder-escaped.
            var resp = client.delete()
                    .uri(b -> b.path("/trade/v2/orders/{id}")
                            .queryParam("AccountKey", "{ak}")
                            .build(brokerOrderId, ctx.accountKey()))
                    .header("Authorization", bearer())
                    .retrieve().toBodilessEntity();
            log.info("saxo response [DELETE /trade/v2/orders/{}]: status={}", brokerOrderId, resp.getStatusCode());
            return OrderResult.accepted(brokerOrderId, null, "canceled");
        } catch (BrokerException e) {
            throw e;
        } catch (RestClientResponseException e) {
            log.info("saxo response [DELETE /trade/v2/orders/{}]: status={} body={}",
                    brokerOrderId, e.getStatusCode().value(), rawBody(e));
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

    private static String mapTif(String tif, String defaultValue) {
        if (tif == null) return defaultValue;
        return switch (tif.toLowerCase(Locale.ROOT)) {
            case "gtc" -> "GoodTillCancel";
            case "day" -> "DayOrder";
            default -> defaultValue;
        };
    }

    private static ObjectNode durationNode(String durationType) {
        ObjectNode d = MAPPER.createObjectNode();
        d.put("DurationType", durationType);
        return d;
    }

    /**
     * Builds OrderDuration from a fetched leg's own {@code Duration} node, preserving
     * {@code ExpirationDateTime} when the leg is GoodTillDate — dropping it (as the previous
     * hardcoded {@code durationNode(String)} did) turns a GTD leg into an accidental GTC on
     * PATCH, silently extending its life indefinitely.
     */
    private static ObjectNode durationNode(JsonNode childDuration) {
        ObjectNode d = MAPPER.createObjectNode();
        d.put("DurationType", childDuration.path("DurationType").asString("GoodTillCancel"));
        JsonNode expiry = childDuration.path("ExpirationDateTime");
        if (!expiry.isMissingNode() && !expiry.isNull()) {
            d.put("ExpirationDateTime", expiry.asString(""));
        }
        return d;
    }

    /**
     * Error mapping for order-placement writes (submitBracket/flatten): 400 → parsed
     * ErrorInfo → OrderResult.rejected (an order-level rejection, not an outage); 409 →
     * UNAVAILABLE (duplicate X-Request-ID replay); everything else delegates to readError
     * (404 → NOT_FOUND, 401/403 → UNAVAILABLE re-auth hint, else → UNAVAILABLE).
     *
     * <p>Also the single consolidation point for write-reject response logging: every write
     * call site that doesn't already log its own reject (submitBracket's inline
     * TooFarFromEntryOrder branch logs itself, see above) routes its
     * {@code RestClientResponseException} here, so the response (status + body) is logged
     * exactly once per reject, tagged with the caller-supplied endpoint label.
     */
    private static OrderResult writeError(String endpoint, RestClientResponseException e) {
        int status = e.getStatusCode().value();
        log.info("saxo response [{}]: status={} body={}", endpoint, status, ProviderLogRedactor.redactBody(rawBody(e)));
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

    JsonNode getJson(String label, Function<UriBuilder, URI> uriFn) {
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

    /** Bounded — a runaway/misbehaving `__next` chain stops after this many pages. */
    private static final int MAX_PAGINATION_PAGES = 20;

    /**
     * Follows Saxo's {@code __next} link (an absolute URL to the next page) on list
     * endpoints (orders/positions/netpositions) until it is absent, merging every page's
     * {@code Data} array into one combined node — capped at {@link #MAX_PAGINATION_PAGES}
     * pages so a misbehaving/looping {@code __next} chain can't hang the caller. Without
     * this, any account with more open orders/positions than fit on one page silently loses
     * the overflow (M-T2-adjacent).
     */
    private JsonNode followPagination(JsonNode first) {
        List<JsonNode> allData = new ArrayList<>();
        first.path("Data").forEach(allData::add);
        JsonNode current = first;
        int pages = 1;
        while (pages < MAX_PAGINATION_PAGES) {
            String next = current.path("__next").asString(null);
            if (next == null || next.isBlank()) break;
            current = exchange(() -> client.get().uri(URI.create(next)).header("Authorization", bearer())
                    .retrieve().body(JsonNode.class));
            current.path("Data").forEach(allData::add);
            pages++;
        }
        ObjectNode combined = MAPPER.createObjectNode();
        var arr = MAPPER.createArrayNode();
        allData.forEach(arr::add);
        combined.set("Data", arr);
        return combined;
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
        if (status == 429) {
            return new BrokerException(BrokerException.Kind.NOT_READY,
                    "saxo rate limited (HTTP 429) — retry shortly", e);
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
    static Order parseOrder(JsonNode n, String parentId, String fallbackSymbol) {
        String type = n.path("OpenOrderType").asString("").toLowerCase(Locale.ROOT);
        String symbol = baseSymbol(n.path("DisplayAndFormat").path("Symbol").asString(""));
        if (symbol.isBlank()) {
            symbol = (fallbackSymbol != null && !fallbackSymbol.isBlank()) ? fallbackSymbol : "?";
        }
        BigDecimal[] prices = classifyPrice(n, type);
        return new Order(
                n.path("OrderId").asString(""),
                n.path("ExternalReference").asString(null),
                symbol,
                n.path("BuySell").asString("").toLowerCase(Locale.ROOT),
                bd(n.path("Amount")),
                type,
                n.path("Status").asString("").toLowerCase(Locale.ROOT),
                deriveRole(n, type, parentId), null, null, prices[0], prices[1], parentId, null, null);
    }

    /**
     * Maps Saxo's own price field to limitPrice/stopPrice — {@code Price} on a bracket parent,
     * {@code OrderPrice} on a leg embedded in {@code RelatedOpenOrders} (a parent never carries
     * OrderPrice, a leg never carries Price, so reading Price-else-OrderPrice covers both
     * shapes). Classified by the node's own {@code OpenOrderType} (already lowercased, {@code
     * type} param): a stop type ({@code type.contains("stop")}, e.g. StopIfTraded/Stop/
     * TriggerStop/TrailingStopIfTraded) maps the price to stopPrice, otherwise (Limit,
     * TriggerLimit, Market, ...) to limitPrice. {@code StopLimitPrice}, when present (a
     * StopLimit order's limit sub-price, live-verified via {@link #submitBracket}'s
     * {@code slLimit} branch), is treated as limitPrice while the Price/OrderPrice value
     * becomes stopPrice regardless of the contains("stop") check — defensive, not live-verified
     * on a get_orders response. {@code hasNonNull} guards keep an absent field genuinely null,
     * never the zero-defaulting {@link #bd(JsonNode)} behavior.
     *
     * @return a 2-element array: {@code [limitPrice, stopPrice]}
     */
    private static BigDecimal[] classifyPrice(JsonNode n, String type) {
        BigDecimal price = n.hasNonNull("Price") ? bd(n.path("Price"))
                : n.hasNonNull("OrderPrice") ? bd(n.path("OrderPrice")) : null;
        BigDecimal stopLimitPrice = n.hasNonNull("StopLimitPrice") ? bd(n.path("StopLimitPrice")) : null;
        if (stopLimitPrice != null) {
            return new BigDecimal[] { stopLimitPrice, price };
        }
        if (type.contains("stop")) {
            return new BigDecimal[] { null, price };
        }
        return new BigDecimal[] { price, null };
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

    /**
     * Position market value. Saxo's {@code Exposure} is the live mark-to-market, but on the
     * delayed SIM/paper feed it — and {@code CurrentPrice} — read 0 (CurrentPriceType "None";
     * confirmed empirically 2026-07-13 for PSMT), while {@code AverageOpenPrice} and
     * {@code ProfitLossOnTrade} are populated. So when Exposure carries no live value,
     * reconstruct it as cost basis + P/L: {@code qty*avgOpen + unrealizedPl}, which equals
     * {@code qty*currentPrice} by definition. Exposure is preferred whenever it is non-zero.
     */
    static BigDecimal marketValue(BigDecimal exposure, BigDecimal qty, BigDecimal avgOpen,
            BigDecimal unrealizedPl) {
        if (exposure != null && exposure.signum() != 0) return exposure;
        return qty.multiply(avgOpen).add(unrealizedPl);
    }

    /**
     * Per-unit market price. Saxo's NetPositionView.CurrentPrice reads 0 on the delayed
     * SIM/paper feed (CurrentPriceType "None"; confirmed 2026-07-13 for PSMT), so derive
     * from the already-reconstructed total: marketValue / qty. Null when qty is 0.
     */
    static BigDecimal perUnitPrice(BigDecimal marketValue, BigDecimal qty) {
        if (marketValue == null || qty == null || qty.signum() == 0) return null;
        return marketValue.divide(qty, 12, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    /**
     * Position direction in the BUY/SELL vocabulary, derived from the SIGN of
     * {@code NetPositionBase.Amount}.
     *
     * <p>Saxo's net position carries no {@code side}/{@code BuySell} field at all — unlike an
     * order, where direction is explicit. Long/short is encoded purely in the sign of the
     * amount: positive = long, negative = short. This is the same convention the shipped
     * {@link #flatten} path already depends on, which closes a position by submitting the
     * opposite side: {@code opposite = amount.signum() > 0 ? "Sell" : "Buy"} — i.e. a positive
     * amount is closed by selling (it is a long), a negative one by buying it back (a short).
     *
     * <p>{@code amount == 0} is a flat net position and has NO direction. It returns null
     * rather than defaulting to BUY: a zero position that claims to be long is a guess, and
     * the consumer cannot tell the guess from a real long. Same reasoning as
     * {@link #perUnitPrice}, which returns null on a zero quantity instead of dividing.
     */
    static String sideFromAmount(BigDecimal amount) {
        if (amount == null || amount.signum() == 0) return null;
        return amount.signum() > 0 ? "BUY" : "SELL";
    }

    /** Reads a string field, returning null (not "") when absent/null — for optional Position fields. */
    static String textOrNull(JsonNode node, String field) {
        return node.path(field).asString(null);
    }

    static BigDecimal bd(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return BigDecimal.ZERO;
        if (node.isNumber()) return node.decimalValue();
        try { return new BigDecimal(node.asString("0")); }
        catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }
}
