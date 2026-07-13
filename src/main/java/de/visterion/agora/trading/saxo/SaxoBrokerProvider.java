package de.visterion.agora.trading.saxo;

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
        JsonNode resp = followPagination(getJson("/port/v1/orders/me?FieldGroups=DisplayAndFormat"));
        List<Order> out = new ArrayList<>();
        for (JsonNode n : resp.path("Data")) {
            Order parent = parseOrder(n, null, null);
            if (status == null || status.isBlank() || parent.status().equalsIgnoreCase(status)) {
                out.add(parent);
            }
            JsonNode children = n.path("RelatedOpenOrders");
            if (children.isArray()) {
                // Legs embedded in RelatedOpenOrders rarely carry their own DisplayAndFormat —
                // fall back to the parent's already-resolved symbol so a leg never surfaces
                // with an empty symbol; only truly orphaned legs (no parent symbol either) get "?".
                for (JsonNode c : children) {
                    Order leg = parseOrder(c, parent.brokerOrderId(), parent.symbol());
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

        ObjectNode fullBody = entryFields.deepCopy();
        var children = MAPPER.createArrayNode();
        children.add(takeProfit);
        children.add(stopLoss);
        fullBody.set("Orders", children);

        String requestId = req.clientRef() != null ? req.clientRef() : UUID.randomUUID().toString();

        try {
            JsonNode resp = client.post().uri("/trade/v2/orders")
                    .header("Authorization", bearer())
                    .header("X-Request-ID", requestId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(fullBody)
                    .retrieve().body(JsonNode.class);
            log.info("saxo response [POST /trade/v2/orders (bracket)]: status=success body={}", resp);
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
                            rawBody(e), code, message, req.symbol());
                    log.info("saxo far-stop: bracket rejected TooFarFromEntryOrder for {} (stop {} vs entry {}), "
                            + "falling back to entry + standalone stop",
                            req.symbol(), req.stopLossStop(), req.limitPrice());
                    return submitFarStopFallback(req, ri, ctx, opposite, entryFields);
                }
            }
            return writeError("POST /trade/v2/orders (bracket)", e);
        } catch (Exception e) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "saxo submitBracket failed: " + e.getMessage(), e);
        }
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
                                                AccountContext ctx, String opposite, ObjectNode entryBody) {
        String entryId;
        try {
            JsonNode resp = client.post().uri("/trade/v2/orders")
                    .header("Authorization", bearer())
                    .header("X-Request-ID", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(entryBody)
                    .retrieve().body(JsonNode.class);
            log.info("saxo response [POST /trade/v2/orders (far-stop entry)]: status=success body={}", resp);
            entryId = resp == null ? null : resp.path("OrderId").asString(null);
        } catch (RestClientResponseException e) {
            // Nothing has been placed yet — safe to report as a plain reject, same as the
            // CLEAN path's error mapping.
            return writeError("POST /trade/v2/orders (far-stop entry)", e);
        } catch (Exception e) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "saxo submitBracket (far-stop entry) failed: " + e.getMessage(), e);
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
            log.info("saxo response [POST /trade/v2/orders (far-stop stop)]: status=success body={}", resp);
            stopId = resp == null ? null : resp.path("OrderId").asString(null);
        } catch (Exception e) {
            if (e instanceof RestClientResponseException rce) {
                log.info("saxo response [POST /trade/v2/orders (far-stop stop)]: status={} body={}",
                        rce.getStatusCode().value(), rawBody(rce));
            }
            stopFailure = e;
        }

        if (stopId == null) {
            return protectUnprotectedEntry(entryId, req.symbol(), stopFailure);
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
    private OrderResult protectUnprotectedEntry(String entryId, String symbol, Exception stopFailure) {
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
        return OrderResult.rejected("standalone stop placement failed: " + cause, "STOP_PLACEMENT_FAILED");
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
            log.info("saxo response [PATCH /trade/v2/orders (leg)]: status=success body={}", resp);
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
        JsonNode resp = followPagination(getJson("GET /port/v1/netpositions (flatten)", b -> b.path("/port/v1/netpositions")
                .queryParam("ClientKey", "{ck}")
                .queryParam("AccountKey", "{ak}")
                .queryParam("FieldGroups", "{fg}")
                .build(ctx.clientKey(), ctx.accountKey(), "NetPositionBase,NetPositionView,DisplayAndFormat")));

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
        for (JsonNode n : related.orders()) {
            String type = n.path("OpenOrderType").asString("");
            boolean protectiveType = type.contains("Stop") || "Limit".equalsIgnoreCase(type);
            boolean oppositeSide = opposite.equalsIgnoreCase(n.path("BuySell").asString(""));
            if (protectiveType && oppositeSide) {
                String legOrderId = n.path("OrderId").asString(null);
                if (legOrderId != null) {
                    try {
                        cancel(legOrderId);
                    } catch (Exception e) {
                        related = related.withError("failed to cancel related order " + legOrderId
                                + ": " + e.getMessage());
                    }
                }
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
            log.info("saxo response [POST /trade/v2/orders (flatten)]: status=success body={}", resp2);
            String orderId = resp2 == null ? null : resp2.path("OrderId").asString(null);
            BigDecimal remainingQty = available.subtract(closeQty);
            String status = related.error() == null ? "accepted"
                    : "accepted (warning: " + related.error() + ")";
            return OrderResult.accepted(orderId, null, status, effectiveCloseQty, remainingQty, null);
        } catch (RestClientResponseException e) {
            return writeError("POST /trade/v2/orders (flatten)", e);
        } catch (Exception e) {
            throw new BrokerException(BrokerException.Kind.UNAVAILABLE,
                    "saxo flatten failed: " + e.getMessage(), e);
        }
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
        log.info("saxo response [{}]: status={} body={}", endpoint, status, rawBody(e));
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
        JsonNode n = exchange(() -> client.get().uri(uri).header("Authorization", bearer())
                .retrieve().body(JsonNode.class));
        logRead("GET " + uri, n);
        return n;
    }

    JsonNode getJson(String label, Function<UriBuilder, URI> uriFn) {
        JsonNode n = exchange(() -> client.get().uri(uriFn).header("Authorization", bearer())
                .retrieve().body(JsonNode.class));
        logRead(label, n);
        return n;
    }

    /**
     * High-frequency reads (positions/account/orders/instrument-details/probe) are logged at
     * DEBUG rather than INFO — reconcile polls hit these constantly, so INFO would spam the
     * log — and the {@link JsonNode} body is only serialized to a log string when DEBUG is
     * actually enabled, per the file's convention of guarding non-trivial log-arg work.
     */
    private static void logRead(String endpoint, JsonNode body) {
        if (log.isDebugEnabled()) {
            log.debug("saxo response [{}]: body={}", endpoint, body);
        }
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
            logRead("GET " + next + " (pagination continuation)", current);
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
        return new Order(
                n.path("OrderId").asString(""),
                n.path("ExternalReference").asString(null),
                symbol,
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
