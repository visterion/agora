# Exit tools: flatten, modify_bracket, place_bracket, get_orders / get_order_by_ref

This page documents the exact contract of Agora's position-exit tools, per broker
(Alpaca, Saxo), so a consumer (Dracul) can reliably manage brackets end-to-end: place
one, modify its legs, partially close a position, and reconcile fills by reading orders
back out. It also lists genuine broker-capability gaps found while implementing this —
called out explicitly rather than guessed.

## `flatten` — partial/full close

### Tool schema

`flatten(connection, symbol, fraction?, qty?)`

- Neither `fraction` nor `qty` → full close (equivalent to `fraction=1.0`).
- `fraction`: `0 < fraction <= 1`. Rejected (`unavailable`) outside that range.
- `qty`: must be positive. Rejected (`unavailable`) if `<= 0`.
- `fraction` and `qty` together is rejected (`unavailable`, "mutually exclusive") — the
  tool never guesses which one wins.
- The tool does **not** know the position size, so it cannot itself reject a `qty` that
  exceeds the position — that check happens broker-side (see below) and comes back as
  `accepted:false` with a `rejectReason`/`rejectCode`, not a broker outage.

### Response shape

```json
{ "accepted": true, "orderId": "...", "clientRef": "...", "status": "...",
  "closedQty": "...", "remainingQty": "...", "avgFillPrice": "..." }
```

`closedQty`/`remainingQty`/`avgFillPrice` are omitted entirely when the broker didn't
supply them (they are never fabricated) — treat their absence as "unknown", not "zero".

### Alpaca

`DELETE /v2/positions/{symbol}` accepts either `qty` (share count) or `percentage`
(0-100) as a query param, never both. `fraction` is converted to `percentage =
fraction*100`; `qty` is passed through verbatim. Alpaca validates the requested size
against the live position itself — a `qty`/`percentage` that exceeds the position comes
back as a 403/422, which this provider maps to `OrderResult.rejected` (not a thrown
exception) exactly like the pre-existing full-close error mapping.

The DELETE response is the resulting closing order object:
- `closedQty` ← `qty` on that response (the requested close size).
- `avgFillPrice` ← `filled_avg_price`, when present (often null — market close orders are
  frequently still working when the DELETE call returns, so the fill is not synchronous).
- `remainingQty` is **always null for Alpaca** — Alpaca's closing-order response carries
  no "remaining position size" field, and this provider does not make an extra
  `GET /positions/{symbol}` call to compute it (would double the request cost per
  flatten). **Genuine gap**: if Dracul needs remaining size after a partial Alpaca close,
  it must call `get_positions` itself afterward.

### Saxo

Saxo has **no partial-close endpoint**. This provider places a single opposite-side
Market order sized to the requested close quantity — the same shape as a full flatten,
just with a smaller `Amount`:

- `qty` given: used directly. If `qty > |position amount|`, rejected with
  `rejectCode=QTY_EXCEEDS_POSITION` **without ever calling the broker**.
- `fraction` given: `closeQty = floor(|position amount| * fraction)` — truncation to a
  whole share, since this provider has no lot-size table for non-stock asset classes.
  If that truncates to 0 (e.g. `fraction=0.1` on a 1-share position), rejected with
  `rejectCode=QTY_ROUNDED_TO_ZERO` without calling the broker. **Documented limitation**:
  for asset classes where the minimum tradable unit isn't 1 whole share (e.g. some FX or
  fractional-share setups), this truncation may be wrong — Dracul should avoid small
  fractional closes on Saxo until a real lot-size table is added.
- Neither given: full close, `closeQty = |position amount|` (unchanged from before).

`closedQty`/`remainingQty` are computed locally (`available - closeQty`) since Saxo's
placement response carries only `{"OrderId": "..."}`. `avgFillPrice` is **always null**
for Saxo flatten — a Market order's placement response has no synchronous fill price.

## `modify_bracket` — orderId semantics (read this before calling it)

`modify_bracket(connection, orderId, stop?, target?)` patches the stop-loss and/or
take-profit level of an *existing* bracket. **The correct `orderId` to pass differs by
broker and is not interchangeable:**

Both brokers resolve legs the same two-step way: **parent lookup first, symbol fallback
second.** The caller always passes the bracket parent's id (from `place_bracket`'s
`orderId`) *and* the symbol — the symbol is what makes the fallback possible once the
parent is gone.

### Saxo — parent lookup, then post-fill symbol fallback

SIM-verified (see `saxo-sim-spike.md` referenced in code comments): pre-fill, only the
bracket parent shows up as a top-level entry in `/port/v1/orders/me` (its
`OrderRelation` is `IfDoneMaster`); the SL/TP legs are **embedded** in that parent's
`RelatedOpenOrders[]`, each carrying its own `OrderId`. `modify_bracket` looks the parent
up by the id you pass, reads its `RelatedOpenOrders[]`, and PATCHes each affected leg
individually with the correct field.

**Post-fill**, the parent id vanishes entirely and the legs detach into
sibling-referencing `Oco` orders with no parent backlink. When that happens (or the
parent id is otherwise not found), `modify_bracket` falls back to a **symbol-based
lookup**: it resolves the symbol to Saxo's `Uic` (via the same instrument resolver
`place_bracket` uses), re-scans the flat `/port/v1/orders/me` list for top-level working
orders matching that `Uic`, classifies them by `OpenOrderType` (contains `"Stop"` → SL
leg, `"Limit"` → TP leg), and PATCHes the requested one(s) directly — no parent needed.
If the fallback can't find a leg satisfying the request either (symbol unresolvable, or
no matching working order), the call still 404s with the same `NOT_FOUND` semantics as
before. This makes the post-fill ratchet use-case (move the stop once the entry fills)
work without any special-casing on the consumer side.

### Alpaca — leg-aware, mirrors Saxo's parent-lookup + symbol-fallback pattern

Alpaca's `PATCH /orders/{id}` only accepts fields that apply to *that specific order*: a
stop order accepts `stop_price`, a limit order accepts `limit_price`. The bracket
*parent* (the entry order) accepts neither meaningfully — its own price field is the
entry limit price, not the stop/target. `modify_bracket` therefore takes the **bracket
parent id + symbol** the same way Saxo's does: it fetches the parent via
`GET /orders/{id}?nested=true`, classifies its embedded `legs[]` (`type: "stop"` /
`"stop_limit"` → stop-loss leg, `type: "limit"` → take-profit leg), and PATCHes each
leg with **only its own price field** — `stop_price` on the SL leg id, `limit_price` on
the TP leg id. A single call can move both stop and target; each leg PATCH is issued
separately.

If the parent lookup 404s (post-fill, id no longer resolvable — Alpaca detaches bracket
legs into independent working orders once the entry fills, same shape as Saxo's `Oco`
legs), `modify_bracket` falls back to `GET /orders?status=open&symbols=<symbol>`,
classifies the flat list of working orders the same way (`type: "stop"`/`"stop_limit"` →
SL, `"limit"` → TP), and PATCHes the requested leg directly. This is largely a safety
net on Alpaca (nested lookup already tends to reflect post-fill legs), but keeps the
resolution strategy — and the ratchet use-case it enables — identical across both
brokers. If the requested leg still isn't found by either lookup (e.g. only a TP leg
exists and a stop change was requested), the call is rejected with `LEG_NOT_FOUND`
rather than silently PATCHing the wrong order.

## `place_bracket` — response shape

```json
{ "accepted": true, "orderId": "<parent id>", "clientRef": "...", "status": "...",
  "stopLegId": "...", "takeProfitLegId": "..." }
```

`stopLegId`/`takeProfitLegId` are omitted when unknown (never fabricated). **Dracul
should persist these alongside `orderId`** — they are the ids needed for a later
leg-aware `modify_bracket` call on Alpaca (see above), and are useful on Saxo too even
though Saxo's own `modify_bracket` works off the parent id (they let Dracul correlate
`get_orders` rows back to "this is bracket X's stop leg" without re-deriving it).

- **Alpaca**: parsed directly from the create-bracket response's `legs[]` array
  (`type: "stop"|"stop_limit"` → `stopLegId`, `type: "limit"` → `takeProfitLegId`). Always
  available synchronously — no extra call.
- **Saxo**: the placement response never contains child leg ids (only the parent
  `OrderId`). This provider does a **best-effort follow-up** `GET /port/v1/orders/me`
  immediately after a successful placement and reads the new parent's
  `RelatedOpenOrders[]`, mirroring the same lookup `modify_bracket` already does. If that
  follow-up fails or the parent isn't visible yet (eventual consistency), the placement
  is still reported `accepted` — the leg ids are simply left null. Callers should treat
  null leg ids as "look them up later via `get_orders`", not as failure.

## `get_orders` / `get_order_by_ref` — field list

```json
{ "brokerOrderId": "...", "clientRef": "...", "symbol": "...", "side": "buy|sell",
  "qty": "...", "type": "...", "status": "...", "role": "entry|stop_loss|take_profit|other",
  "filledQty": "...", "avgFillPrice": "...", "parentId": "..." }
```

- `role`: `"entry"` for a bracket's parent order, `"stop_loss"`/`"take_profit"` for its
  legs, `"other"` for a standalone (non-bracket) order. Always present (never null).
- `parentId`: the parent's `brokerOrderId` for a leg, `null` for a top-level order. This
  is how a consumer answers "which bracket does this leg belong to."
- `filledQty`/`avgFillPrice`: nullable, broker-dependent (see below). **This is how a
  consumer determines "which bracket leg filled at what price"**: call `get_orders`,
  filter to `parentId == <bracket orderId>`, and read `status`/`filledQty`/`avgFillPrice`
  on the matching leg row.

### Alpaca

`get_orders` now requests `nested=true`, so a bracket parent's response carries its legs
in an embedded `legs[]` array; this provider **flattens** those legs into the returned
list as their own `Order` entries (`parentId` = parent's id), rather than leaving them
nested — so `get_orders` returns one row per leg, not just per bracket. `role` for a
top-level order is `"entry"` when Alpaca's `order_class` is `bracket`/`oco`/`oto`, else
`"other"`. `filledQty` ← `filled_qty` (Alpaca always returns this, `"0"` when unfilled —
so it's populated, not null, for every order). `avgFillPrice` ← `filled_avg_price`
(explicitly `null` in Alpaca's JSON until a fill occurs — mapped through as null).
`get_order_by_ref` (single lookup by `client_order_id`) does **not** flatten legs — it
returns only the matched order itself. **Minor gap**: if you look up a bracket parent by
its clientRef, you get the parent row only; to see its legs, call `get_orders` instead.

### Saxo

`get_orders` flattens each bracket parent's embedded `RelatedOpenOrders[]` the same way
(mirrors the leg-detection pattern already used by `modify_bracket`/`place_bracket`'s
follow-up lookup): `role="entry"` for a parent with `OrderRelation="IfDoneMaster"`, else
`"other"`; a leg's role is derived from its own `OpenOrderType` (`Stop*` → `stop_loss`,
`Limit` → `take_profit`). **Genuine gap, left undone rather than guessed**:
`filledQty`/`avgFillPrice` are **always null for every Saxo order**.
`/port/v1/orders/me` is an *open*-orders endpoint, and this implementation could not
verify — without live Saxo credentials in this sandbox — a reliable fill-qty/fill-price
field on it (a filled bracket leg likely disappears from this endpoint entirely, per the
same post-fill detachment behavior documented for `modify_bracket`). **Workaround for
Dracul**: to observe a Saxo leg's fill, poll `get_positions`/`get_account` for the net
effect, or (once available) a Saxo activity/trades endpoint — not covered by this change.

## Summary of gaps left explicitly documented (not guessed, not fixed)

1. **Alpaca flatten** never returns `remainingQty` (broker response doesn't carry it);
   Dracul must call `get_positions` separately if it needs that number.
2. **Saxo flatten** partial-close truncates to whole units with no lot-size table —
   fine for ordinary equities, unverified/likely-wrong for fractional-unit asset classes.
3. **Saxo orders** never expose `filledQty`/`avgFillPrice` — `/port/v1/orders/me` is an
   open-orders view and a verified fill-detail field could not be confirmed without live
   credentials.
