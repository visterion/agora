# Agora capabilities

Complete inventory of what Agora exposes today. Contract detail for individual tools lives
in the live MCP/webhook schemas, in [`api.md`](api.md) (fundamentals, earnings), and in
[`exit-tools.md`](exit-tools.md) (trading exits). Provider coverage is in
[`hunting-grounds.md`](hunting-grounds.md).

**38 tools** · three logical surfaces · one Docker image.

| Surface | Package(s) | Role |
|---|---|---|
| **agora-data** | `data`, `fetch/*` | Read-only market data, company/fundamentals, SEC, earnings, index |
| **agora-research** | `research`, `research/fundamentals` | Indicators (ta4j), R-framework, fundamental scores/concepts |
| **agora-trading** ⚠ | `trading`, `trading/saxo` | Execution (webhook + trading token only; never on MCP) |

Scope rule: Agora is generic. Domain framing (verdicts, theses, strategy) lives in the
consumer. Agora only passes through an opaque `client_ref` on orders.

---

## Market data

| Tool | Description |
|---|---|
| `get_quote` | Current price and day-change percent (one or more symbols) |
| `get_ohlc` | Daily OHLCV history (oldest-first) |
| `get_intraday` | Intraday OHLCV candles (interval + range) |
| `get_fx_rate` | FX conversion rate: 1 unit of `from` in `to` |

**Provider chain (quotes / OHLC):** Alpaca → Saxo → TwelveData → Finnhub → Yahoo  
**Intraday:** Yahoo chart. **FX:** Yahoo pairs (optional scheduled warmer).

---

## Company, fundamentals, estimates

| Tool | Description |
|---|---|
| `get_company_profile` | Name, industry, exchange, market cap |
| `get_company_news` | Company news headlines merged from multiple sources (Finnhub + configured RSS/Atom feeds); per-item `sourceType` (`news`|`social`) and `domain` (lowercase url host, `www.`-stripped, JSON null when unparsable), partial-failure `warnings`, optional `sourceTypes` filter |
| `get_fundamentals` | Screener-style metrics (US: Finnhub; non-US: computed when global metrics enabled) |
| `get_fundamental_concepts` | Raw normalized line items (US → SEC EDGAR, non-US → Yahoo) |
| `get_fundamental_score` | Standardized health scores; today Piotroski F-score 0–9 + criteria + raw |
| `get_analyst_estimates` | Recommendation trend (buy/hold/sell counts — not EPS/revenue estimates) |
| `get_earnings_estimates` | Reported EPS vs. estimate per period + raw surprise delta |

**News fan-out:** finnhub + RSS/Atom feeds from `agora.data.news.feeds` (default: Yahoo RSS, two Reddit searches tagged `social`), fetched in parallel, deduped (URL, then title), sorted newest-first, capped at `agora.data.news.max-items` (200). Failed feeds degrade to warnings; only total failure is `unavailable`. Each merged item additionally carries a derived `domain` (lowercase URL host, `www.` stripped; null for blank/unparsable URLs) — raw metadata for consumers, no scoring or weighting in Agora.

---

## Earnings

| Tool | Description |
|---|---|
| `get_earnings_calendar` | Recent and upcoming earnings events for a symbol; `partial: true` when the answer may be incomplete |
| `get_earnings_window` | Market-wide earnings events in a date window; `partial` and `truncated` flags |
| `get_eps_history` | Reported quarterly EPS (symbol or CIK) |

**Provider merge (not a fallback chain):** Finnhub (full history) and Nasdaq
(`agora.data.nasdaq.*`, key-less, future-only, no `epsActual`) are queried in parallel
under a shared budget and their results merged; Yahoo is consulted only for symbols
those two left uncovered, and only from its asynchronously warmed page cache — **Yahoo's
calendar index is currently broken server-side (HTTP 500)**, so it stays behind a
failure cooldown and self-heals if the upstream index returns. An empty result from a
provider that could see the window is a valid answer, not an error; `partial: true`
means a needed provider failed, was cooled, ran out of budget, or covered only part of
the window. See
[`hunting-grounds.md`](hunting-grounds.md#earnings-calendar-merge) for the full merge
semantics and cache TTLs, and [`api.md`](api.md#get_earnings_calendar) for the tool
output contract.

**Configuration (`agora.` prefix):**

| Property | Default | Purpose |
|---|---|---|
| `data.yahoo.user-agent` | browser UA string | Single UA for every Yahoo client (crumb, fundamentals-timeseries, price/chart, earnings) |
| `data.nasdaq.base-url` | `https://api.nasdaq.com` | Nasdaq earnings-calendar endpoint |
| `data.nasdaq.user-agent` | browser UA string | Nasdaq rejects non-browser agents |
| `data.nasdaq.day-cap` | `95` | Max calendar days fetched per call; **must stay ≥ 91** — `get_earnings_calendar` defaults to a `now+90` window, so a lower cap would make the tool's most common call permanently partial. The day loop also stops early when the remaining budget can no longer hold another day; either stop marks the answer `partial` |
| `fetch.earnings.attempt-timeout-ms` | `2500` | Per-provider read timeout for one earnings fetch attempt. Not independent of `budget-ms`: limiter wait + 3000ms connect + this must stay strictly below the budget, or a hanging provider is only ever budget-cancelled and therefore never cooled down |
| `fetch.earnings.budget-ms` | `9000` | Total wall-clock budget for the parallel provider fan-out. The earnings limiter wait is *derived* from this and `attempt-timeout-ms` (budget − 3000 connect − attempt − 500 margin); an unworkable combination fails startup |
| `fetch.earnings.partial-ttl-seconds` | `600` | Cache TTL for a `partial` answer (short, to retry soon without hammering) |
| `fetch.earnings.cooldown-threshold` | `3` | Consecutive provider failures before it is cooled down |
| `fetch.earnings.cooldown-ms` | `600000` | How long a cooled-down provider is skipped before being retried |
| `data.finnhub.calls-per-minute` | `60` | Shared rate-limit policy across all eight Finnhub callers (news, fundamentals, estimates, profile, quote, earnings, etc.) — one limiter instance, not per-caller |
| `data.finnhub.max-wait-ms` | `3000` | Bounded wait for a caller that opts to wait out the limiter instead of failing fast (e.g. quotes). Applies to callers with no total call budget; the earnings path overrides it with its budget-derived ceiling (see above) |
| `data.finnhub.default-retry-after-ms` | `2000` | Fallback wait when a Finnhub 429 carries neither `Retry-After` nor a parseable `x-ratelimit-reset` |
| `data.edgar.max-filing-bytes` | `33554432` (32 MiB) | Ceiling on one filing's primary document (`get_filing_text`); an over-cap document is rejected, never truncated — see "Filing size cap" below |
| `data.edgar.max-concurrent-filing-fetches` | `8` | How many filing bodies may be in memory at once. Not independent of `max-filing-bytes`: the two multiply into the service's memory ceiling (~1.25 GiB at the defaults). Over the bound a caller waits 30 s and is then refused with `filing_fetch_busy:` — see "Concurrency bound" below |

---

## SEC / EDGAR

| Tool | Description |
|---|---|
| `get_filings` | Recent filings by symbol/CIK, optional form filter (`limit` default 40, max 100) |
| `search_filings` | Full-text search by form type(s) and date window (`limit` default 100, max 1000) |
| `get_filing_text` | Primary document as cleaned text (~24k chars, SSRF-guarded) |
| `get_company_concept` | Full reported history of one XBRL concept |
| `get_company_facts` | Several `us-gaap` concepts in one upstream fetch |
| `get_form4_transactions` | Market-wide non-derivative Form-4 transactions in a date window (`limit` default 100, max 1000) |
| `get_form4_owner_history` | Multi-year Form-4 history for one company, grouped per owner (`limit` default 200, max 500) |

### The `ticker` field on a filing hit

EFTS carries no `tickers` key. The symbol exists only inside `display_names[0]`, as the
parenthesised group in front of the `(CIK …)` group — `"Arcosa, Inc.  (ACA)  (CIK 0001739445)"`.
That group is treated as a **candidate**, never as an answer, and is emitted only when the
filer's own CIK is listed under it in SEC's `company_tickers.json`.

The reason is that EDGAR conformed names themselves end in parentheticals. Measured over SEC's
full `cik-lookup-data.txt` (1,053,510 names, 2026-08-04): 635 names end in a group that is
ticker-shaped, and 183 of those collide with a real listed ticker of a *different* company.
Live examples: `Grayscale Story Trust (IP)`, `ACUITY INC. (DE)`, `MUZINICH & CO. LTD (UK)`,
`Tower Research Capital LLC (TRC)`. An invented symbol is strictly worse than an absent one —
it routes a quote lookup and a merger spread at the wrong issuer.

EFTS also prints **all** of a filer's symbols in one comma-separated group —
`(EQH, EQH-PA, EQH-PC)`, `(WPP, WPPGF)`, `(IPCX, IPCXR, IPCXU)` — so the group is split and the
first element the CIK is listed under wins (SEC orders that file by market cap descending, so
that is the primary listing). Measured over a 2,442-hit live EFTS sample (DEFM14A, S-4, 425,
20-F; 2026-02-01…2026-08-01, 2026-08-04) this raised correct symbols from 1,499 to 2,053 and
brought wrong ones from 80 to 0.

`ticker` is empty whenever the filer's CIK is not in SEC's ticker file (unlisted filers,
trusts, individuals — and companies delisted after a merger closed), whenever the printed
group matches nothing the CIK is listed under, and whenever `company_tickers.json` is
unavailable. That last case is deliberate: a lookup failure must not degrade back into
guessing. Consumers must treat an empty `ticker` as "no symbol", never as an error, and must
not reconstruct one from `company`.

### Row limits on the market-wide EDGAR tools

`search_filings` and `get_form4_transactions` cap at **1000** rows, aligned with
`get_earnings_window`. The **default stays 100** — a market-wide default of 1000 would make
every caller pay for a full-market scan — so a caller doing a market-wide sweep must pass
`limit` explicitly. A cut result reports `truncated: true`; never read a truncated window as
complete.

Truncation is reported from **two** signals, not one, because the row count alone is not
sufficient at this bound. `MAX_LIMIT` equals the service's `HARD_FETCH_CAP`, so there is zero
headroom: a `limit=1000` answer can only be recognised as cut by its length if it holds
exactly 1000 rows, and a single hit dropped anywhere across the ten fetched pages (a
malformed `file_date`, an unparsable hit) yields 999 — which would have been reported as a
complete window over a window that may hold 50,000 filings. The service therefore also
returns whether its own pagination stopped early, and the tools OR that in.

1000 is also the real ceiling of the upstream. SEC's EFTS endpoint returns at most 100 hits
per page whatever `size` is requested (verified 2026-08-04: `size=200/500/1000` all return
100), so Agora paginates; its own `HARD_FETCH_CAP` stops at 1000 fetched hits — and that
constant is now what the two tools derive their maximum from, so the numbers cannot drift.
EFTS does **not** refuse a too-deep window with an HTTP error: verified live 2026-08-04,
`from=10000` returns **HTTP 200** with an OpenSearch error body
(`{"errorType":"ResponseError","errorMessage":"... Result window is too large, from + size
must be less than or equal to: [10000] ..."}`) and no `hits` key at all. A naive pager reads
that as an exhausted window; Agora treats a response carrying `errorType` as a cut and marks
the result truncated. Separately, `get_form4_transactions` fetches one
archive document per hit under a 110 ms throttle and a 30 s aggregate deadline, so a
market-wide call in practice parses roughly 140 filings before reporting `truncated: true`
— raising `limit` does not lift that second bound.

### How EFTS reads `forms`: root forms, and why `4,4/A` is a trap

The `forms` parameter selects **root** form types, and a root form **already includes its
amendments**. Adding an explicit `X/A` token does not widen the query — it *intersects* the
whole result down to that amendment type, no matter what else is in the list. Measured live
against EFTS on window 2026-07-20..2026-07-27 (`hits.total.value`):

| `forms=` | hits |
|---|---|
| `4` | **1697** (a 100-hit page carried 99 `file_type` `4` **and** 1 `4/A`) |
| `4/A` | 38 |
| `4,4/A` | **38** — intersection, not union |
| `4/A,4` (order swapped) | 38 |
| `forms=4&forms=4/A` (repeated parameter) | 38 |
| `3` | 312 |
| `3,4` | **2009** — exactly 312 + 1697, so CSV across root forms IS a correct union |
| `3,4/A` | **0** — proves the `/A` token is a global narrowing |
| `3,4,4/A` | 38 |

URL-encoding the slash changes nothing (`4%2C4%2FA` → 38), and there is no `root_forms`
field — sending one is simply ignored. So no encoding unions `4` and `4/A`, and none is
needed: `forms=4` is that union. Asking for amendments **only** stays expressible — send
`4/A` on its own.

This was a live production defect: `get_form4_transactions` sent `forms=4,4/A` and so saw
1.6 % of its window, amendments only. Multi-form lists that contain no `/A` token are
unaffected — verified live on window 2026-02-01..2026-08-01: `DEFM14A` 133, `SC TO-T` 123,
`DEFM14A,SC TO-T` **256** = 133 + 123; `10-12B` 34, `10-12G` 93, `10-12B,10-12G` **127** =
34 + 93. Callers of `search_filings` should pass root forms only.

### Form-4 window: the caller's window is searched before the late-filing pad

A Form 4 is filed *after* the trade it reports, so `get_form4_transactions` must look past the
end of the caller's window to catch in-window trades filed late. It does that as **two**
searches over disjoint filing-date ranges, in strict priority order: first `[from, to]`, then
`(to, to+10d]`. The `limit` is shared — the pad search only gets what the window search left
over, and is skipped entirely once the limit is full (which reports `truncated: true`). A cap
hit in either search truncates the whole result.

The order is load-bearing, not cosmetic. EFTS returns hits **`file_date` descending**, and the
fetch budget (1000 hits, then the 30 s deadline over ~110 ms-spaced archive GETs) is an order
of magnitude smaller than a market-wide window — so whichever range sorts first is the only
one that is ever read. Measured live 2026-08-04, market-wide caller window
2026-07-20..2026-07-27, full end-to-end replay against real EFTS and real archive GETs:

| search range | filings read | in-window transactions |
|---|---|---|
| one padded range `[from-10d, to+10d]` (6210 hits) | 143, all filed 2026-08-03 | **0** |
| caller window first `[from, to]` (1697 hits) | 139 | **187** (51 open-market buys, 6 above 500k USD) |

There is deliberately **no backward pad**: a Form 4's `transactionDate` never exceeds its
`file_date`, so a filing filed before `from` cannot carry an in-window transaction (measured
over 100 Form 4s filed 2026-07-10..07-17: 162 of 162 transactions had
`file_date - transactionDate >= 0`). The old symmetric pad spent 10 days of a scarce budget on
filings the window filter was guaranteed to discard.

Consequence for consumers: a market-wide `get_form4_transactions` call is a **sample of the
newest filings in the window**, not the window. It reports `truncated: true`, and clustering
logic must not read an absent cluster as evidence that none exists. Narrow the window (or the
company, via `get_form4_owner_history`) to get complete coverage.

### Filing size cap (`get_filing_text`)

A filing's primary document is read whole into memory, bounded by
`agora.data.edgar.max-filing-bytes` (`AGORA_DATA_EDGAR_MAX_FILING_BYTES`, default
**33554432** = 32 MiB). The previous hard-compiled 5 MB rejected 13 of the 40 most recent
DEFM14A merger proxies (measured 2026-08-04: median 3.53 MB, p90 10.21 MB, max 24.93 MB) —
i.e. exactly the documents that carry deal terms.

An over-cap document is **not** truncated: the text extractor locates the summary section by
its *last* heading occurrence (to skip the table of contents), so a byte-truncated document
would silently yield the TOC instead of the section. It is rejected with a distinct signal
consumers can key on — kind `TOO_LARGE` and an error string beginning `filing_too_large:`,
carrying the measured size, the cap and the property name. A genuinely unreachable source
stays kind `UNAVAILABLE` and never carries that token.

Memory: the read is `byte[]` plus a decoded `String` plus the extractor's intermediates, so
the transient peak is roughly 3x the cap (96–160 MiB at 32 MiB) **per in-flight**
`get_filing_text` call. Only the ≤24k-char extract is cached.

### Concurrency bound on `get_filing_text`

Because that peak is per in-flight call, the number of concurrent calls is what decides the
service's memory ceiling — and that must be a property of Agora, not of who happens to call
it. `agora.data.edgar.max-concurrent-filing-fetches`
(`AGORA_DATA_EDGAR_MAX_CONCURRENT_FILING_FETCHES`, default **8**) bounds it. The arithmetic:
8 × 160 MiB ≈ 1.25 GiB peak. Unbounded it is Tomcat's default 200 request threads, i.e.
~31 GiB. A caller over the bound waits up to 30 s for a slot and is then refused with kind
`UNAVAILABLE` and an error string beginning `filing_fetch_busy:` — a refusal a caller can
retry, rather than a request thread parked indefinitely. Cache hits never take a slot.

**Operator note (not applied by this codebase).** The bound is sized against a heap the JVM
picks for itself. In a Docker-in-LXC deployment the JVM reads the *host's* memory, not the
container's: with no container memory limit, no cgroup limit and no `-Xmx`, `MaxHeapSize`
comes out at ~15.5 GiB inside a 16 GiB container shared with other services, so an
allocation spike is an OOM kill of the whole container with no `OutOfMemoryError` in the log.
The runtime fix belongs in the deployment, not here: give the container a memory limit
(`--memory` / compose `mem_limit`) and let the JVM size from it
(`-XX:MaxRAMPercentage=50`, and `-XX:+UseContainerSupport`, which is on by default but is
defeated by the missing limit). Do both — a percentage of an unlimited container is still
unlimited.

---

## Index / universe

| Tool | Description |
|---|---|
| `get_index_constituents` | Constituent snapshot (default S&P 500 via Wikipedia) |
| `get_index_constituent_changes` | Pending/recent add/remove (`sp_press` for S&P 500; Russell recon PDFs + iShares for Russell 1000/2000) |

---

## Research / technical indicators

| Tool | Description |
|---|---|
| `list_indicators` | Machine-readable indicator catalog |
| `get_indicators` | Compute catalog indicators; composable specs (`of`); optional `series=N` |
| `get_r_framework` | Risk unit and R-multiple price levels (ATR-based stop optional) |
| `ping` | Liveness probe |

**26 built-in indicators** (see README or `list_indicators`): SMA/EMA/WMA/KAMA, MA-cross,
Parabolic SAR, Ichimoku, RSI/ROC/PPO/DPO/MACD/Stochastic/CCI/Williams %R/Aroon, ADX/ATR/
Bollinger/StdDev/MeanDev/Chandelier, Highest/Lowest/52w range/OBV.

Extensible without rebuild: mount YAML and set `AGORA_RESEARCH_INDICATORS_FILE`.

---

## Trading / execution (trading token + named connection)

| Tool | Description |
|---|---|
| `list_connections` | Active connections (id, provider, environment, probe) |
| `get_account` | Equity, buying power, cash, status |
| `get_positions` | Open positions |
| `get_closed_positions` | Closed positions with real fill prices/P&L + open/close time (Saxo); Alpaca signals supported:false → use get_orders. Optional from/to. |
| `get_orders` | Open + historical orders with fills; Alpaca native, Saxo history via audit trail. Optional from/to. |
| `get_order_by_ref` | Lookup by opaque `client_ref`; unknown ref → `available:true` with `order:null`, not an error |
| `place_bracket` | Entry + stop-loss, optional take-profit (omit `takeProfitLimit` → entry+stop only; Saxo only) |
| `modify_bracket` | Change stop-loss and/or take-profit; optional `stopOrderId`/`targetOrderId` address one exact leg (required when a symbol carries more than one bracket, e.g. a multi-tranche position) |
| `cancel_order` | Cancel by broker order id; unknown id → `available:true` with `accepted:false`, `rejectCode:"NOT_FOUND"`, not an error |
| `flatten` | Close entire position via market order |
| `place_protective_stop` | Place ONE protective stop for `qty` shares of an existing position at `stop_price`; purely additive — cancels nothing, reads no other order (Saxo only; Alpaca rejects `PROTECTIVE_STOP_UNSUPPORTED`) |

**Brokers:** Alpaca (paper/live), Saxo (headless OAuth). Selected per connection, no
cross-broker fallback on a single call. Order placements are **not** idempotent on the
broker side — Saxo's `X-Request-ID` is a per-attempt key, so a caller retrying after a
timeout must reconcile via `get_order_by_ref` first. Exit contract details:
[`exit-tools.md`](exit-tools.md).

---

## Internal (no dedicated tool)

| Capability | Role |
|---|---|
| Split providers (Alpaca, Finnhub) | Corporate-action splits; used for EPS/Piotroski share-count adjustment |
| Instrument identity | Ticker / exchange suffix / ISIN → `Instrument` (Saxo UIC when needed) |
| Fundamentals routing | US → EDGAR concepts; non-US → Yahoo timeseries; optional global metrics shape |
| TTL caches | Per-family (prices, news, fundamentals, filings, constituents, …) |
| Provider call logging | Outbound HTTP logging with secret redaction — [`observability.md`](observability.md) |
| Live access guard | Separate tokens for general / trading / live / live-readonly |

---

## Surfaces and ops

| Endpoint | Purpose |
|---|---|
| `/mcp` | MCP Streamable HTTP (general tools only) |
| `POST /tools/{name}` | Webhook invocation (includes trading) |
| `GET /tools` | Tool catalog (trading filtered out) |
| `/actuator/health` | Health |
| `/auth/saxo/*` | Saxo OAuth callback/session |

Auth: `BearerTokenFilter` on `/tools/**` and `/mcp/**`. Trading tools accept only a trading
token; live connections need stronger live scopes.

---

## Tool count checklist (38)

```
cancel_order
flatten
get_account
get_analyst_estimates
get_closed_positions
get_company_concept
get_company_facts
get_company_news
get_company_profile
get_earnings_calendar
get_earnings_estimates
get_earnings_window
get_eps_history
get_filing_text
get_filings
get_form4_owner_history
get_form4_transactions
get_fundamental_concepts
get_fundamental_score
get_fundamentals
get_fx_rate
get_index_constituent_changes
get_index_constituents
get_indicators
get_intraday
get_ohlc
get_order_by_ref
get_orders
get_positions
get_quote
get_r_framework
list_connections
list_indicators
modify_bracket
ping
place_bracket
place_protective_stop
search_filings
```

When adding or removing a tool: update this list, the README tool catalog, and the local
`CLAUDE.md` index in the same change.

## Deploy checks

- Before deploying a Yahoo UA change: confirm neither `AGORA_DATA_YAHOO_USER_AGENT`
  nor `AGORA_DATA_YAHOO_CRUMB_USER_AGENT` is set in the environment
  (`docker exec agora env | grep -i YAHOO` must be empty), so a stale override
  cannot feed a bot UA to the crumb path.
