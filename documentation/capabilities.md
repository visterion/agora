# Agora capabilities

Complete inventory of what Agora exposes today. Contract detail for individual tools lives
in the live MCP/webhook schemas, in [`api.md`](api.md) (fundamentals), and in
[`exit-tools.md`](exit-tools.md) (trading exits). Provider coverage is in
[`hunting-grounds.md`](hunting-grounds.md).

**36 tools** · three logical surfaces · one Docker image.

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
| `get_earnings_calendar` | Recent and upcoming earnings events for a symbol |
| `get_earnings_window` | Market-wide earnings events in a date window |
| `get_eps_history` | Reported quarterly EPS (symbol or CIK) |

**Provider chain:** Finnhub → Yahoo.

---

## SEC / EDGAR

| Tool | Description |
|---|---|
| `get_filings` | Recent filings by symbol/CIK, optional form filter |
| `search_filings` | Full-text search by form type(s) and date window |
| `get_filing_text` | Primary document as cleaned text (~24k chars, SSRF-guarded) |
| `get_company_concept` | Full reported history of one XBRL concept |
| `get_company_facts` | Several `us-gaap` concepts in one upstream fetch |
| `get_form4_transactions` | Market-wide non-derivative Form-4 transactions in a date window |
| `get_form4_owner_history` | Multi-year Form-4 history for one company, grouped per owner |

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
| `get_orders` | Open and recent orders |
| `get_order_by_ref` | Lookup by opaque `client_ref` |
| `place_bracket` | Entry + stop-loss + take-profit |
| `modify_bracket` | Change stop-loss and/or take-profit |
| `cancel_order` | Cancel by broker order id |
| `flatten` | Close entire position via market order |

**Brokers:** Alpaca (paper/live), Saxo (headless OAuth). Selected per connection, no
cross-broker fallback on a single call. Exit contract details: [`exit-tools.md`](exit-tools.md).

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

## Tool count checklist (36)

```
cancel_order
flatten
get_account
get_analyst_estimates
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
search_filings
```

When adding or removing a tool: update this list, the README tool catalog, and the local
`CLAUDE.md` index in the same change.
