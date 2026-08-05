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
  "closedQty": "...", "remainingQty": "...", "avgFillPrice": "...",
  "protective_legs": [ { "replaces": "...", "order_id": "...", "qty": "...", "price": "..." } ],
  "legs_collapsed": false }
```

`closedQty`/`remainingQty`/`avgFillPrice` are omitted entirely when the broker didn't
supply them (they are never fabricated) — treat their absence as "unknown", not "zero".

### Partial close restores protective legs (Saxo)

A partial close on Saxo (`remainingQty > 0`) restores the cancelled protective orders
sized to the remainder before placing the closing Market order, rather than leaving the
remaining shares unprotected. **Monotonicity**: a restored leg is never larger than the
order it replaces — a 5-share stop next to a 20-share close shrinks to at most 5, never
grows.

The response carries `protective_legs` — each entry's `replaces` is the id the caller
already has on file, `order_id` the new id Saxo issued — and `legs_collapsed`. **Both
fields appear on the rejected branch too**: when a partial close fails after the legs
were already cancelled and rolled back, Saxo issues brand-new order ids for the restored
legs even though the close itself never went through. Callers **must** persist these new
ids on every branch — the stop ratchet addresses legs by id, and a caller that only reads
`protective_legs` on `accepted:true` ends up pointing at cancelled orders after any
rejected partial close, failing every later stop modification with `LEG_NOT_FOUND`. Both
fields are omitted on a full close (nothing was restored) and on any flatten call that
never touched a protective leg.

`legs_collapsed:true` means the remainder was too small to give every leg at least one
share (e.g. two stop legs but only one share left after the close) — the allocator folds
all protection into the single tightest stop rather than dropping any leg to zero.

**Two known limitations**, both already documented above and unchanged by this restore
logic: Saxo flatten's whole-share truncation with no lot-size table, and the window
between the closing order being accepted and it actually filling, during which the
holding briefly exceeds what the newly sized-down stops cover.

**`remainingQty` semantics differ by broker**: Saxo's `remainingQty` is a **projected**
value (`available - closeQty`, computed before the market order fills); Alpaca's
`remainingQty` is the **actual** live position read after the close. Both mean "remaining
after close", but Saxo's isn't a confirmed post-fill figure.

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
- `remainingQty` — Alpaca's closing-order response carries no "remaining position size"
  field, so this provider backfills it with a follow-up `GET /positions/{symbol}` after
  the DELETE is accepted (one extra request, only on the close path). Returns the live
  `qty` on that position, or `0` when the position is fully gone (404). If that follow-up
  read itself errors, `remainingQty` comes back `null` rather than failing the close.

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

**Restore mechanics.** Before placing the closing Market order, this provider cancels
the position's protective (opposite-side Stop/Limit) legs and, on a partial close, puts
them back sized to the remainder — restoration happens *before* the close, so a restore
failure costs nothing (only cancels have happened) rather than being discovered after a
filled, now-unprotected close. Only legs that were **successfully cancelled** are
eligible for restoration: a leg whose price/id/amount couldn't be read back is left alone
(uncancelled) on a partial close rather than being cancelled with nothing to put back —
but on a full close (nothing is being restored anyway) it is still cancelled, matching
the plain pre-restore flatten behaviour. If a cancel itself fails partway through, the
legs that did cancel cleanly are put back at full size and the trim is rejected
(`LEG_CANCEL_INCOMPLETE` / `LEG_RESTORE_FAILED` / `LEG_RESTORE_FAILED_UNPROTECTED`)
rather than leaving a partially-protected mix.

A rollback — restoring cancelled legs to full size after a restore or close failure —
**interleaves cancel-then-place per leg** rather than cancelling every sized leg first
and placing every full-size leg after: cancelling everything before placing anything back
can leave the position fully naked if a failure (e.g. a sustained rate limit) hits between
the two phases. Interleaving means protective coverage never drops to zero and the
opposite-side interest working against the holding never exceeds it, even mid-rollback.

Whether a rollback happens at all is governed by a **determinate/indeterminate split** on
the closing order's own failure: a determinate failure (400/401/403/404/429 — the broker's
synchronous response says the close was *not* placed) is safe to roll back, since no
broker state changed beyond the leg restore/cancels already done. An indeterminate failure
(409 duplicate-request replay, 5xx, or no response at all) means the close may have
actually gone through and only the response was lost — restoring full-size protection on
top of a close that silently filled would leave *more* opposite-side interest working than
the position holds, so this case escalates as a broker error instead of guessing, leaving
the legs exactly as sized to the remainder.

## `modify_bracket` — orderId semantics (read this before calling it)

`modify_bracket(connection, orderId, symbol, stop?, target?, stopOrderId?, targetOrderId?)`
patches the stop-loss and/or take-profit level of an *existing* bracket. **The correct
`orderId` to pass differs by broker and is not interchangeable:**

Both brokers resolve legs the same two-step way: **parent lookup first, symbol fallback
second.** The caller always passes the bracket parent's id (from `place_bracket`'s
`orderId`) *and* the symbol — the symbol is what makes the fallback possible once the
parent is gone (`symbol` is a required parameter on this tool, not optional).

### Naming the leg: `stopOrderId` / `targetOrderId` (optional, additive)

Resolution — by parent or by symbol — is only unambiguous while the instrument carries
**one** bracket. A position built in more than one tranche has **two protective stops
working on the same instrument**, and the by-symbol fallback then has to guess: Saxo's
keeps the last `Stop`-type order it scans, Alpaca's refuses with `AMBIGUOUS_LEGS`.
Neither is a way to trail a stop.

Pass `stopOrderId` (and/or `targetOrderId`) to address one exact order. Both brokers then
skip resolution entirely, verify the named order really is that leg type, and PATCH it.
Omitting both is the pre-existing behaviour, unchanged — no existing caller is affected.

Leg ids come from `place_bracket`'s `stopLegId` / `takeProfitLegId`, or from `get_orders`.

Rules and reject codes:

| Situation | Result |
|---|---|
| Neither id given | Parent lookup → symbol fallback, exactly as before |
| `stopOrderId` names a working stop order | That order is PATCHed; no lookup, no ambiguity |
| Named id matches no working order | `LEG_NOT_FOUND` — nothing is patched |
| Named id is not that leg type (e.g. the entry order) | `LEG_TYPE_MISMATCH` — nothing is patched |
| One leg named, another leg re-priced unnamed | `LEG_ID_REQUIRED` — half-explicit is refused, since the unnamed leg would fall back to the very guess you are trying to avoid |
| Provider without leg addressing | `LEG_ADDRESSING_UNSUPPORTED` — stated, never silently ignored |

The named order is looked up in **both** shapes Saxo produces — a detached top-level
order once its tranche has filled, and a leg still embedded in an unfilled parent's
`RelatedOpenOrders[]`. Both occur at the same time on a real two-tranche book (observed
2026-08-04: one symbol with two working detached `StopIfTraded` orders covering one
position, another symbol whose second-tranche stop was still embedded in its unfilled
parent).

**Non-atomic per-leg modify**: modify is per-leg and NOT atomic — if the stop leg is
moved and the take-profit PATCH is then rejected, the result is `accepted:false` but the
stop may already have moved. Re-read via `get_orders` to reconcile. (True for both
brokers.)

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
no matching working order), the call is **rejected** with `rejectCode=LEG_NOT_FOUND`
(`accepted:false`, not a thrown error) — the same uniform shape Alpaca uses for its
equivalent "leg genuinely doesn't exist" case (see below). This makes the post-fill
ratchet use-case (move the stop once the entry fills) work without any special-casing on
the consumer side, and lets the consumer treat "leg not found" identically across
brokers without branching on which one is active.

**Assumption / self-id exclusion:** the fallback is designed for ONE bracket's detached
protective legs per symbol (Dracul holds one position per symbol). It explicitly excludes
the caller's own bracket parent id from the `Uic`-matching scan. This matters for a subtle
edge case: "parent found but its `RelatedOpenOrders[]` is empty" also triggers the
fallback (same as parent-not-found), and in that state the parent order itself still
appears in `/port/v1/orders/me` sharing the resolved `Uic`. Without the exclusion, the
scan could misclassify the entry order as a stop/take-profit leg (its `OpenOrderType` is
typically `"Limit"`, matching the TP classification) and PATCH it — corrupting the entry
price instead of correctly rejecting with `LEG_NOT_FOUND`.

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

## `place_bracket` — optional take-profit

`takeProfitLimit` is **optional**. Omit it to place an entry plus a protective stop and
no take-profit leg — the shape needed when a synthesised target (e.g. 3R) sits outside
Saxo's proximity band and would otherwise get the whole bracket rejected with
`400 TooFarFromEntryOrder`. It is the same body the far-stop fallback below produces,
only requested deliberately instead of reached reactively after a reject.

- **Saxo**: supported. The child `Orders[]` array then carries the stop leg only, and
  `takeProfitLegId` is absent from the response.
- **Alpaca**: rejected — `order_class=bracket` requires both legs and `order_class=oto`
  is not wired up. The call fails with an explicit "requires a take-profit leg" message
  rather than guessing at unverified semantics.

The entry-vs-stop relation is still validated in both directions (`buy` needs
`limitPrice > stopLossStop`, `sell` the reverse) — dropping the take-profit never drops
the protective-stop sanity check.

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
  `RelatedOpenOrders[]`, mirroring the same lookup `modify_bracket` already does.
  Immediately after placement the parent+legs may not be visible yet (Saxo eventual
  consistency), so this follow-up is a **bounded retry** — up to 3 attempts, ~200ms
  apart — that stops as soon as leg ids are found (the common case costs exactly one
  `GET` and no delay). If leg ids are still null after the retry window (lookup keeps
  failing, or the parent/legs never appear within the window), the placement is still
  reported `accepted` — the leg ids are simply left null. Callers should treat null leg
  ids as "look them up later via `get_orders`", not as failure.

### Saxo — far-stop fallback (entry + standalone stop, no take-profit)

Saxo enforces a proximity band on a bracket's stop-loss (`TooFarFromEntryOrder`); a
requested stop outside that band rejects the whole bracket. `place_bracket` places the
bracket directly (no dry-run) and the fallback is detected reactively when Saxo rejects the
bracket with `TooFarFromEntryOrder`: that reject is atomic (Saxo either accepts the whole
bracket body or rejects it wholesale — nothing is ever placed on this path), so on that
specific reject `place_bracket` switches to placing the entry order alone followed by a
**standalone `StopIfTraded`** at the requested stop level — with **no take-profit leg**
(Dracul manages such positions' exits itself via a trailing chandelier, so a lone entry/stop
needs no TP). **Fail-safe:** if the standalone stop cannot be placed,
the entry is canceled (or, if it already filled before the cancel landed, the resulting
position is flattened instead) — an entry is never left without a protective stop. The
fallback result still uses `stopLegId` for the standalone stop's id, with `takeProfitLegId`
left null; any other bracket reject short-circuits to a plain `rejected(...)` with no
further fallback, and every bracket reject (regardless of code) is logged for diagnosis.

**The named leg is the one Saxo actually rejected.** Saxo's 400 body carries a per-leg
`Orders[]` array in addition to the top-level `ErrorInfo`; the log line and the reject
message are derived from that array, not from a hard-coded assumption. Legs marked
`OrderNotPlaced` ("order not placed as other order in request was rejected") are
**collateral damage** and are skipped — the first leg with a real error code is the
culprit. This matters: a bracket whose take-profit sits too far out rejects with the
take-profit named and the stop merely `OrderNotPlaced`. Agora previously logged the stop
in that case, which sent diagnosis down the wrong path. If the body carries no per-leg
information, the leg is reported as `unknown` rather than guessed. (Index → role follows
the order legs are built in: with a take-profit, index 0 is the TP and index 1 the stop;
without one, index 0 is the stop. Tests pin both shapes.)

**The original reject reason survives the fallback.** If the fallback itself fails, the
message names both causes rather than only the last one — e.g.

```
bracket rejected [TooFarFromEntryOrder am take_profit 237.71]; fallback dann gescheitert: saxo rate limited
```

This holds for a rejected/failed fallback entry as well as for the
`STOP_PLACEMENT_FAILED` path, so a consumer never sees a bare downstream error with the
actual trigger lost.

**Spacing:** the fallback waits `FAR_STOP_DELAY_MS` (1000 ms) before POSTing the entry.
Previously it fired ~90 ms after the rejected bracket and tripped Saxo's own rate limit —
in all five observed production incidents the fallback never got through, so it had never
actually worked. The 1000 ms is a **hypothesis**, not a measured limit from Saxo's
documentation: it is a plausible spacing, and whether it is enough stays verifiable from
the logs (a fallback that still comes back rate-limited now says so with the original
bracket reject attached).

### Request-ID semantics (Saxo)

Every Saxo order POST — the bracket, the fallback entry, the standalone stop, a flatten —
sends a **fresh `X-Request-ID` per attempt**. Saxo deduplicates on that header, so a value
kept stable across retries is burned after the first use: a bracket rejected with 400 and
then retried under the same id comes back `409` forever. The header is therefore a
per-attempt key, nothing more.

The stable **business** key is `ExternalReference` (= `clientRef`) in the body; that is
what identifies the order across attempts and what `get_order_by_ref` looks up.

**Consequence for the caller:** Saxo's dedup is no longer a safety net against duplicate
orders after a client-side timeout. If a call times out and its outcome is unknown, the
caller must reconcile before retrying — read back by `clientRef` (`get_order_by_ref`) and
adopt an existing order instead of placing a second one. Dracul does exactly this on the
entry path.

## `get_order_by_ref` — an unknown ref is a negative result, not an outage

An unknown `clientRef` returns `available:true` with `{"order": null}` — "the lookup ran
fine, there is no such order". It is **not** an `unavailable` error envelope. Genuine
outages (broker down, not ready / rate-limited) still return `available:false`, so the
caller can tell "no such order" apart from "ask again later" and retry only the latter.

This matters precisely for the reconcile-then-retry flow above: a caller adopting an
order by `clientRef` must be able to read "none exists" as a green light to place, rather
than as a broker failure that blocks the placement.

## `cancel_order` — an unknown order id is a rejection, not an outage

Cancelling an order id the broker does not know returns `available:true` with
`accepted:false`, `rejectCode:"NOT_FOUND"` and the broker's message in `rejectReason` —
a definite "this did not happen", not a retriable transport failure. Real outages
(broker down, not ready / rate-limited) still return `available:false`.

Note the deliberate asymmetry with `get_order_by_ref`: there, "not found" is flattened
into a plain success (`order:null`), because the question was "does it exist?" and "no"
is the complete answer. For a cancel it is **not** reported as an idempotent success,
because at the broker "not found" is ambiguous — the order may be already cancelled or
expired (intent fulfilled), it may have **filled** and left the working book (a live
position exists), or the id may simply be wrong. A silent success would let a caller book
a filled entry as "cancelled" and stop guarding a real position. The distinguishable
rejection lets a caller that can reconcile order/position state branch on `NOT_FOUND`,
while a caller that cannot keeps the safe reading: "did not happen", not "retry me".

## `get_orders` / `get_order_by_ref` — field list

```json
{ "brokerOrderId": "...", "clientRef": "...", "symbol": "...", "side": "buy|sell",
  "qty": "...", "type": "...", "status": "...", "role": "entry|stop_loss|take_profit|other",
  "filledQty": "...", "avgFillPrice": "...", "limitPrice": "...", "stopPrice": "...",
  "parentId": "...", "submittedAt": "...", "filledAt": "..." }
```

- `role`: `"entry"` for a bracket's parent order, `"stop_loss"`/`"take_profit"` for its
  legs, `"other"` for a standalone (non-bracket) order. Always present (never null).
- `parentId`: the parent's `brokerOrderId` for a leg, `null` for a top-level order. This
  is how a consumer answers "which bracket does this leg belong to."
- `filledQty`/`avgFillPrice`: nullable, broker-dependent (see below). **This is how a
  consumer determines "which bracket leg filled at what price"**: call `get_orders`,
  filter to `parentId == <bracket orderId>`, and read `status`/`filledQty`/`avgFillPrice`
  on the matching leg row.
- `limitPrice`/`stopPrice`: nullable `BigDecimal`, broker-dependent (see the Saxo/Alpaca
  subsections below) — an order's limit and/or stop price. A plain market order carries
  neither; a bracket's take-profit leg typically carries only `limitPrice`, its stop-loss
  leg typically carries only `stopPrice`, and a stop-limit order may carry both.
- `submittedAt`/`filledAt`: nullable ISO-8601 timestamps, omitted when the broker doesn't
  supply them (never fabricated).

### `from`/`to` params

`get_orders(connection, status?, from?, to?)` takes optional ISO-8601 UTC bounds on order
submission time, broker-mapped:

- **Alpaca**: mapped to `after`/`until` query params — boundary-exclusive on Alpaca.
- **Saxo**: presence of `from`/`to` (or `status ∈ {closed, all}`) routes the call to the
  history endpoint (see below); Saxo's open-orders endpoint has no date filter of its own.

### Saxo — two-endpoint split (open vs. history)

`get_orders` on Saxo hits one of two different endpoints depending on the call:

- **Open path** (default, no `from`/`to`, `status` not in `{closed, all}`):
  `GET /port/v1/orders/me` — the existing open-orders view. `filledQty`/`avgFillPrice` are
  **always null** here (see gap #2 below); `role` is derived from bracket structure as
  before (`RelatedOpenOrders[]`).
- **History path** (`from`/`to` given, or `status ∈ {closed, all}`):
  `GET /cs/v1/audit/orderactivities` with `EntryType=Last` (collapses each order's activity
  trail to its latest state — one row per order) and `FromDateTime`/`ToDateTime` when a
  range is given. This path carries **real fills**: `filledQty` ← `FilledAmount`,
  `avgFillPrice` ← `AveragePrice`, `submittedAt`/`filledAt` ← `ActivityTime`. **Trade-off**:
  this endpoint has no bracket-leg relationship data, so every history row comes back with
  `role="other"` and `parentId=null` — a consumer cannot reconstruct "which bracket did this
  leg belong to" from history rows alone (only the open path can do that). On the history
  path, `status ∈ {closed, all}` acts purely as a **router** that selects this audit
  endpoint — it does **not** filter the returned rows. Each returned order carries its
  real Saxo status (e.g. `fill`/`finalfill`/`cancelled`) for the consumer to filter
  client-side; Saxo's audit `Status` values (`Placed`/`Changed`/`Fill`/`FinalFill`/
  `Cancelled`/`Expired`) don't map onto the generic `closed`/`all` routing keywords.

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

`limitPrice`/`stopPrice` ← Alpaca's documented `limit_price`/`stop_price` fields (JSON
string or null), read on every order object, parent and `legs[]` entries alike. **Schema-
inferred, not live-verified**: mapped defensively from Alpaca's published order schema
(no live paper/live account trace captured for this), so both fields are read via a
null-safe guard and simply come back null if the field is absent or unexpectedly shaped.

### Saxo

`get_orders` flattens each bracket parent's embedded `RelatedOpenOrders[]` the same way
(mirrors the leg-detection pattern already used by `modify_bracket`/`place_bracket`'s
follow-up lookup): `role="entry"` for a parent with `OrderRelation="IfDoneMaster"`, else
`"other"`; a leg's role is derived from its own `OpenOrderType` (`Stop*` → `stop_loss`,
`Limit` → `take_profit`). **Partial gap, open path only**: `filledQty`/`avgFillPrice` are
**always null on the open path** (`/port/v1/orders/me`), which remains a pure
open-orders view (a filled bracket leg likely disappears from this endpoint entirely, per
the same post-fill detachment behavior documented for `modify_bracket`). They **are
populated on the history path** (`/cs/v1/audit/orderactivities`, entered via `from`/`to`
or `status ∈ {closed, all}`) — see the Saxo two-endpoint split above for the trade-off
(history rows lose bracket-leg `role`/`parentId`). **For Dracul**: to read a Saxo leg's
fill, call `get_orders` with a `from`/`to` range or `status ∈ {closed, all}` rather than
the default open-path call.

`limitPrice`/`stopPrice` ← the node's own `Price` (bracket parent) or `OrderPrice` (a leg
embedded in `RelatedOpenOrders`), classified by the node's own `OpenOrderType`: a stop
type (contains "Stop", e.g. `StopIfTraded`/`Stop`/`TriggerStop`/`TrailingStopIfTraded`)
maps to `stopPrice`, otherwise (`Limit`, `TriggerLimit`, `Market`, ...) to `limitPrice`. A
`StopLimit` order's `StopLimitPrice`, when present, is treated as `limitPrice` while
`Price`/`OrderPrice` becomes `stopPrice` (defensive — not live-verified). **Live-verified**:
this mapping was checked against a real Saxo SIM bracket response captured in prod logs
(parent `Price:182.53`; take-profit leg `OpenOrderType:Limit, OrderPrice:226.03`;
stop-loss leg `OpenOrderType:StopIfTraded, OrderPrice:168.03`) — the open path only, same
as `filledQty`/`avgFillPrice` above; the history path never carries these price fields.

## `get_closed_positions` — field list, range filter, Alpaca gap

```json
{ "closedPositions": [ { "symbol": "...", "uic": "...", "openPrice": "...",
  "closePrice": "...", "amount": "...", "profitLoss": "...", "clientRef": "...",
  "openTime": "...", "closeTime": "...", "openingPositionId": "..." } ],
  "supported": false, "windowLimited": true, "note": "..." }
```

- `openTime`/`closeTime`: nullable ISO-8601 timestamps of when the position was opened
  and closed.
- `openingPositionId`: nullable. **This is a Saxo *position* id, not an order id** — it
  correlates a closed position back to the position that opened it, but it cannot be
  passed to `get_order_by_ref` or any order-oriented tool.
- `get_closed_positions(connection, from?, to?)`: `from`/`to` are optional ISO-8601 UTC
  bounds on close time. On Saxo these are applied as a **client-side filter over the
  broker's current rolling window**, not a broadened server-side query — Saxo's closed
  positions view only ever covers its own rolling window, so `from`/`to` narrows what's
  already in that window rather than fetching further back. Whenever `from` or `to` is
  given, the response carries `"windowLimited": true` plus a `note` explaining this.
- **Alpaca**: no native closed-positions source. The response comes back with
  `"supported": false`, an empty `closedPositions` array, and a `note` pointing the
  consumer at `get_orders` instead (Alpaca's order history carries the same fill data a
  consumer would otherwise read off a closed position).

## Summary of gaps left explicitly documented (not guessed, not fixed)

1. **Saxo flatten** partial-close truncates to whole units with no lot-size table —
   fine for ordinary equities, unverified/likely-wrong for fractional-unit asset classes.
2. **Saxo orders — open path only.** `filledQty`/`avgFillPrice` are still always null on
   the **open** path (`/port/v1/orders/me`), which remains a pure open-orders view. They
   are **now populated on the history path** (`/cs/v1/audit/orderactivities`, entered via
   `from`/`to` or `status ∈ {closed, all}`) — at the cost of losing bracket-leg role/parent
   info on that path (see the Saxo two-endpoint split above). No remaining gap for reading
   a Saxo fill; the trade-off is which endpoint (and which fields) you get it from.
