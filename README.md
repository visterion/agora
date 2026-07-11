# Agora
<img width="2163" height="717" alt="image" src="https://github.com/user-attachments/assets/1d64694e-59db-4337-b771-38566451057f" />

> A broker and provider agnostic MCP tool suite for market data, quant research, and
> trade execution. Register a tool once in Agora and every consumer can use it without a
> rebuild.

[![docker](https://github.com/visterion/agora/actions/workflows/docker.yml/badge.svg)](https://github.com/visterion/agora/actions/workflows/docker.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/java-25-blue)](https://openjdk.org)
[![Spring Boot](https://img.shields.io/badge/spring%20boot-4.0-6DB33F)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/spring%20ai-2.0-6DB33F)](https://spring.io/projects/spring-ai)
[![MCP](https://img.shields.io/badge/MCP-Streamable%20HTTP-6E56CF)](https://modelcontextprotocol.io)
[![lines of code](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/visterion/agora/main/badges/loc.json&cacheSeconds=300)](https://github.com/visterion/agora)

**Docker image:** [`ghcr.io/visterion/agora:main`](https://github.com/visterion/agora/pkgs/container/agora)

---

Agora is a standalone service that exposes a catalog of financial tools (quotes, OHLC,
technical indicators, fundamentals, SEC filings, earnings calendars, and broker execution)
over the [Model Context Protocol](https://modelcontextprotocol.io) (MCP) and a plain HTTP
webhook. Consumers such as AI agents and trading systems call these tools without knowing
which data provider or broker sits behind them.

It's built as a single Spring Boot 4 / JDK 25 application and ships as one Docker image.

---

## Why Agora exists

Agora is one product in a family of sibling services:

| Service | Role |
|---|---|
| **Vistierie** | Infrastructure / agent platform |
| **HiveMem** | Long-term memory |
| **Agora** | Market data, quant, and execution (**this repo**) |
| **Dracul** | A *consumer*: an investment-research agent that uses Agora |

The principle we build around (the **Nordstern**):

> A new tool, provider, broker, or quant building block is registered **in Agora**.
> The consumer stays untouched, and no consumer rebuild is required.

Dracul, and any future consumer, gains capabilities purely by Agora adding a tool, never
by changing consumer code. That's why Agora is a separate service rather than a library
baked into each agent.

### Scope discipline: the boundary

Agora is generic. It owns raw market data, execution, and neutral quant math. It must
never contain investment domain vocabulary.

| Agora owns | The consumer (e.g. Dracul) owns |
|---|---|
| Raw market data, execution, pure quant | Domain shaping (spinoffs, mergers, earnings theses) |
| Plugin registry, MCP transport, auth, health | Verdicts, decisions, provenance |
| Normalized, broker-/provider-neutral domain (Account/Order/Position/Fill) | Anomaly screening, framing, prompts, orchestration |

**Rule:** if a word like `spinoff`, `insider`, `PEAD`, `verdict`, or `thesis` shows up in an
Agora tool name, schema, or code, that's a layer-violation bug. Investment provenance
lives only in the consumer, which passes an opaque `client_ref` that Agora just echoes back.

The **two-consumer rule** is the check on this: once a second consumer exists alongside
Dracul, it proves the API is genuinely neutral.

---

## Architecture

Agora runs as one Spring Boot application. A single tool registry is served through two
front-doors, the MCP endpoint and an HTTP webhook, and both expose the same set of tools.

<img width="1513" height="939" alt="image" src="https://github.com/user-attachments/assets/a9c24702-7453-4ff8-9832-fd6cee1cb025" />

**Every tool is a Spring `@Component` implementing the `AgoraTool` interface:**

```java
public interface AgoraTool {
    String name();
    String description();
    ObjectNode inputSchema();          // JSON Schema for the arguments
    ToolResult call(JsonNode args);
    default String namespace() { return "general"; }   // "trading" = gated + webhook-only
}
```

Because the `ToolRegistry` collects every `AgoraTool` bean, adding one class exposes the
tool on both front-doors at once:

- `McpToolAdapter` turns each tool into an MCP `SyncToolSpecification` on `/mcp`.
- `ToolWebhookController` serves each tool at `POST /tools/{name}` (Vistierie-compatible).
- `ToolCatalogController` mirrors the catalog at `GET /tools` for discovery.

Both the MCP adapter and the catalog **filter out `trading`-namespace tools**, so execution
is never exposed over MCP or the public catalog. It is reachable only over the authenticated
webhook.

### Logical grouping

Even though it's one binary, the code is organized into the three logical servers from the
design spec:

| Group | Package(s) | Content |
|---|---|---|
| **agora-data** | `data`, `fetch/*` | Read-only market data: quotes, OHLC, intraday, FX, news, fundamentals, filings, earnings, index constituents. Provider plugins. |
| **agora-research** | `research` | Pure quant/analysis: ATR, Chandelier, MA-cross, RSI, MACD, Bollinger, ADX, CCI, Stochastic, Williams %R, OBV, 52-week range, R-framework. Built on [ta4j](https://github.com/ta4j/ta4j). |
| **agora-trading** ⚠ | `tools` (namespace `trading`) | Execution: account, positions, orders, bracket place/modify, flatten, cancel, list connections. Broker plugins (Alpaca and Saxo), selected per named connection. |

---

## Tool catalog

32 tools today. `general` tools are on MCP, webhook, and catalog; `trading` tools are
webhook-only and require a trading token.

### Market data (`agora-data`)

| Tool | Description |
|---|---|
| `get_quote` | Current price and day-change percent for one or more symbols |
| `get_ohlc` | Daily OHLCV history (oldest-first) |
| `get_intraday` | Intraday OHLCV candles at a given interval/range |
| `get_fx_rate` | Current FX conversion rate (1 unit of `from` in `to` currency) |
| `get_company_profile` | Company profile: name, industry, exchange, market cap |
| `get_company_news` | Recent company news headlines |
| `get_fundamentals` | Fundamental metrics for a symbol |
| `get_analyst_estimates` | Analyst recommendation trend |
| `get_earnings_estimates` | Reported EPS vs. estimate per period with the raw surprise delta (actual − estimate) — raw passthrough, no scoring |
| `get_index_constituents` | Constituents of a stock index (default S&P 500) |

### Fundamentals & SEC filings (`agora-data`)

| Tool | Description |
|---|---|
| `get_filings` | Recent SEC filings (by symbol or CIK), optionally filtered by form |
| `search_filings` | SEC EDGAR full-text search by form type(s) and date window |
| `get_filing_text` | Fetch a SEC filing's primary document as cleaned text, extracting the summary/term-sheet section when present (fallback: a leading text window), truncated to ~24k chars. Input `url` — an archive document URL as returned by `search_filings`. Neutral and form-agnostic. Output `{ text, section_found, truncated, char_count, source_url }`. SSRF-guarded: only URLs under the configured SEC archive base are fetched. |
| `get_company_concept` | Full reported history of any XBRL company-concept (e.g. `us-gaap/Assets`) |
| `get_eps_history` | Reported quarterly EPS history (by symbol or CIK) |
| `get_form4_transactions` | Non-derivative SEC Form-4 transactions (beneficial-ownership changes) |
| `get_earnings_calendar` | Recent and upcoming earnings events for a symbol |
| `get_earnings_window` | Market-wide earnings events reported in a date window (one row per company) |
| `get_fundamental_score` | Standardized fundamental-health scores computed from SEC XBRL company facts. Input: `symbol`. Output: a `scores` object; today `piotroskiF` (Piotroski F-score) with `score` (0-9), `criteriaAvailable` (0-9 — a criterion counts as available only if it could be strictly evaluated; met criteria still require verifiable evidence, otherwise they score 0), per-criterion `criteria.<name>.{met, available}`, and `raw` underlying figures (`roa`, `cfo`, `netIncome`, `accrualRatio`, `currentRatio`, `grossMargin`, `assetTurnover`). Degrades to `unavailable` on EDGAR errors. `scores` is extensible — future scores will be added as siblings of `piotroskiF`. |

### Research / technical indicators (`agora-research`)

| Tool | Description |
|---|---|
| `list_indicators` | Machine-readable indicator catalog: names, params with defaults, outputs |
| `get_indicators` | Computes any set of catalog indicators in one call — composable specs (`{name, params, of, label}`), optional `series=N` for the last N values |
| `get_r_framework` | Risk unit and R-multiple price levels |
| `ping` | Liveness probe that returns `pong` plus any echoed message |

The catalog ships with **26 built-in indicators**, computed via `get_indicators` (single
values or `series=N` for the last N). Each indicator has named params (with defaults) and
one or more named outputs.

**Trend & moving averages**

| Indicator | Description | Default params | Outputs |
|---|---|---|---|
| `sma` | [Simple Moving Average](https://en.wikipedia.org/wiki/Moving_average) | `period` 20 | `value` |
| `ema` | [Exponential Moving Average](https://en.wikipedia.org/wiki/Moving_average) | `period` 20 | `value` |
| `wma` | [Weighted Moving Average](https://en.wikipedia.org/wiki/Moving_average) | `period` 20 | `value` |
| `kama` | [Kaufman Adaptive Moving Average](https://en.wikipedia.org/wiki/Moving_average) | `barCount` 10, `fastBarCount` 2, `slowBarCount` 30 | `value` |
| `ma_cross` | [Moving-average cross](https://en.wikipedia.org/wiki/Moving_average) | `fast` 50, `slow` 200 | `fast`, `slow` |
| `parabolic_sar` | [Parabolic SAR](https://en.wikipedia.org/wiki/Parabolic_SAR) | default acceleration | `value` |
| `ichimoku` | [Ichimoku Kinkō Hyō](https://en.wikipedia.org/wiki/Ichimoku_Kink%C5%8D_Hy%C5%8D) | defaults | `tenkan`, `kijun`, `senkou_a`, `senkou_b`, `chikou` |

**Momentum & oscillators**

| Indicator | Description | Default params | Outputs |
|---|---|---|---|
| `rsi` | [Relative Strength Index](https://en.wikipedia.org/wiki/Relative_strength_index) | `period` 14 | `value` |
| `roc` | [Rate of Change (momentum)](https://en.wikipedia.org/wiki/Momentum_%28technical_analysis%29) | `period` 12 | `value` |
| `ppo` | [Percentage Price Oscillator](https://en.wikipedia.org/wiki/MACD) | `fast` 12, `slow` 26 | `value` |
| `dpo` | [Detrended Price Oscillator](https://en.wikipedia.org/wiki/Detrended_price_oscillator) | `period` 20 | `value` |
| `macd` | [MACD line, signal & histogram](https://en.wikipedia.org/wiki/MACD) | `fast` 12, `slow` 26, `signal` 9 | `macd`, `signal`, `histogram` |
| `stochastic` | [Stochastic oscillator](https://en.wikipedia.org/wiki/Stochastic_oscillator) | `k` 14, `d` 3 | `k`, `d` |
| `cci` | [Commodity Channel Index](https://en.wikipedia.org/wiki/Commodity_channel_index) | `period` 20 | `value` |
| `williams_r` | [Williams %R](https://en.wikipedia.org/wiki/Williams_%25R) | `period` 14 | `value` |
| `aroon` | [Aroon up/down/oscillator](https://en.wikipedia.org/wiki/Technical_analysis) | `period` 25 | `up`, `down`, `oscillator` |

**Trend strength & volatility**

| Indicator | Description | Default params | Outputs |
|---|---|---|---|
| `adx` | [Average Directional Index](https://en.wikipedia.org/wiki/Average_directional_movement_index) | `period` 14 | `value` |
| `atr` | [Average True Range (SMA of True Range)](https://en.wikipedia.org/wiki/Average_true_range) | `period` 22 | `value` |
| `bollinger` | [Bollinger Bands](https://en.wikipedia.org/wiki/Bollinger_Bands) | `period` 20, `k` 2.0 | `upper`, `middle`, `lower` |
| `stddev` | [Standard deviation over a window](https://en.wikipedia.org/wiki/Standard_deviation) | `period` 20 | `value` |
| `mean_deviation` | [Mean absolute deviation over a window](https://en.wikipedia.org/wiki/Average_absolute_deviation) | `period` 20 | `value` |
| `chandelier_stop` | [Chandelier stop (ATR-based trailing stop)](https://en.wikipedia.org/wiki/Average_true_range) | `period` 22, `multiple` 3.0 | `value` |

**Range, volume & helpers**

| Indicator | Description | Default params | Outputs |
|---|---|---|---|
| `highest` | [Highest value over a window](https://en.wikipedia.org/wiki/Donchian_channel) | `period` 20 | `value` |
| `lowest` | [Lowest value over a window](https://en.wikipedia.org/wiki/Donchian_channel) | `period` 20 | `value` |
| `52w_range` | [52-week high/low over fetched history](https://en.wikipedia.org/wiki/Donchian_channel) | `minBars` 250 | `high`, `low` |
| `obv` | [On-Balance Volume](https://en.wikipedia.org/wiki/On-balance_volume) | — | `value` |

<sub>Wikipedia links are English. A few indicators (`kama`, `ma_cross`, `ppo`, `aroon`, `chandelier_stop`, `52w_range`) have no dedicated English Wikipedia article and link to the closest related concept.</sub>

Beyond these, `get_r_framework` returns risk-unit and R-multiple price levels.

Indicators are **composable** — feed one into another via `of`, e.g. an RSI-of-SMA:
`{"name":"sma","params":{"period":5},"of":{"name":"rsi"}}`. Operators can add any simple
ta4j indicator without a rebuild: mount a YAML file and set `AGORA_RESEARCH_INDICATORS_FILE`
(same format as `indicators-catalog.yaml`), then restart. The live, machine-readable catalog
is always available via `list_indicators`.

### Trading / execution (`agora-trading`, trading token required)

| Tool | Description |
|---|---|
| `get_account` | Account summary: equity, buying power, cash, status |
| `get_positions` | All open positions |
| `get_orders` | All open and recent orders |
| `get_order_by_ref` | Look up an order by client reference ID (`client_ref`) |
| `place_bracket` | Place a bracket order (entry + stop-loss + take-profit) |
| `modify_bracket` | Modify the stop-loss and/or take-profit of an existing bracket |
| `flatten` | Close (flatten) the entire position for a symbol via market order |
| `cancel_order` | Cancel an open order by broker order id |
| `list_connections` | List active trading connections (id, provider, environment, probe status) visible to the caller |

---

## Providers & brokers (plugins)

Data tools resolve through provider plugins with fallback, so the consumer never picks a
provider.

| Domain | Plugins |
|---|---|
| Quotes / OHLC / intraday | Alpaca (broker feed, IEX) first, then Saxo (non-US exchanges via the `saxo-live` session, Yahoo-suffix symbols like `SAP.DE`, 15-min delayed), then TwelveData, Finnhub, then keyless Yahoo Finance as last-resort fallback |
| Company profile / news / fundamentals / estimates | Finnhub |
| Filings / XBRL concepts / EPS / Form-4 | SEC EDGAR |
| Earnings calendar | Finnhub, Yahoo |
| Index constituents | Wikipedia (S&P 500) |
| Execution | Alpaca and Saxo, selected per named connection (`alpaca-paper`, `alpaca-live`, `saxo-sim`, `saxo-live`) |

Responses are cached with per-family TTLs (prices 120s, news 15m, fundamentals 6h,
filings 1h, constituents 24h), all configurable.

**Supported brokers:** Alpaca and Saxo. Both expose headless, self-contained REST auth
that Agora refreshes without a human in the loop, which fits the deployment model.

> **Interactive Brokers (IBKR):** evaluated and dropped. IBKR restricts first-party
> OAuth to Financial Advisor and Organizational accounts, so an individual account has
> no sensible headless auth flow — the only officially supported path is the Client
> Portal Gateway, a persistent local process with interactive login, which is
> incompatible with Agora's headless model. Execution stays on Alpaca and Saxo.

---

## Security

Guarded by `BearerTokenFilter` on `/tools/**` and `/mcp/**`:

- **General tools and `/mcp`**: accept either a general or a trading token.
- **Trading tools** (`/tools/{name}` where `namespace()=="trading"`): accept **only**
  trading tokens.
- **Live connections** (`saxo-live`, `alpaca-live` — real money): gated by a third,
  disjoint token set `AGORA_TRADING_LIVE_TOKENS`. Only a token in that set can see or
  call a live connection; a normal trading token sees the paper/sim connections only.
  Enforced by `LiveAccessGuard`.
- **Read-only live tokens** (`AGORA_TRADING_LIVE_TOKENS_READONLY`): a fourth token set
  that can see live connections (`list_connections`) and call trading **read** tools
  (`get_account`, `get_positions`, `get_orders`) on them, but cannot mutate — order
  submission, modification, cancellation, and flatten on a live connection still require
  a full `AGORA_TRADING_LIVE_TOKENS` token. Read tools stamp a top-level `asOf`
  (ISO-8601 instant) on their output so callers know how fresh the data is.
- **`/actuator/health`**: public, no token.
- Fail-closed: an empty token config denies all tool calls.

Tokens are comma-separated, one per consumer, supplied via env vars.

---

## Configuration

All configuration is via environment variables (see `src/main/resources/application.yaml`
for the full list and defaults). Key ones:

| Variable | Purpose |
|---|---|
| `AGORA_AUTH_TOKENS` | Comma-separated general (read/quant) bearer tokens |
| `AGORA_TRADING_TOKENS` | Comma-separated execution bearer tokens (paper/sim connections) |
| `AGORA_TRADING_LIVE_TOKENS` | Comma-separated tokens that unlock live connections (`saxo-live`, `alpaca-live`); a disjoint set from `AGORA_TRADING_TOKENS`, enforced by `LiveAccessGuard` |
| `AGORA_TRADING_LIVE_TOKENS_READONLY` | Comma-separated tokens that can see live connections and call trading read tools (`get_account`, `get_positions`, `get_orders`) on them, but cannot mutate; a disjoint set, enforced by `LiveAccessGuard` |
| `AGORA_TRADING_ALPACA_KEY_ID` / `_SECRET` / `_BASE_URL` | Alpaca **paper** credentials (`alpaca-paper`; defaults to paper API) |
| `AGORA_TRADING_ALPACA_LIVE_KEY_ID` / `_SECRET` / `_BASE_URL` | Alpaca **live** credentials (`alpaca-live`) |
| `AGORA_TRADING_SAXO_SIM_APP_KEY` / `_APP_SECRET` / `_BASE_URL` / `_REDIRECT_URI` | Saxo SIM developer-app credentials + OAuth redirect (`saxo-sim`) |
| `AGORA_TRADING_SAXO_LIVE_APP_KEY` / `_APP_SECRET` / `_BASE_URL` / `_REDIRECT_URI` | Saxo LIVE developer-app credentials + OAuth redirect (`saxo-live`) |
| `AGORA_TRADING_SAXO_TOKEN_DIR` | Directory for persisted Saxo OAuth tokens (default `/data/saxo`) |
| `AGORA_TRADING_SAXO_REFRESH_CHECK_MS` | Saxo token auto-refresh check interval in ms (default `30000`) |
| `AGORA_TRADING_PROVIDER_TIMEOUT_MS` | Response timeout for broker/OAuth HTTP calls in ms (default `10000`; connect timeout is fixed at 3 s) |
| `AGORA_DATA_ALPACA_KEY_ID` / `_SECRET` / `_BASE_URL` | Alpaca Market Data credentials (broker-first quote/OHLC + splits; IEX feed). Blank = provider self-skips |
| `AGORA_DATA_FINNHUB_KEY` | Finnhub API key |
| `AGORA_DATA_TWELVEDATA_KEY` | TwelveData API key |
| `AGORA_DATA_PROVIDER_TIMEOUT_MS` | Per-request read timeout for market-data providers, so a slow upstream fails fast into the next (default `4000`) |
| `AGORA_FETCH_TIMEOUT_MS` | Read timeout for EDGAR/Finnhub/Wikipedia/Yahoo-earnings fetch clients in ms (default `15000`; generous for multi-MB EDGAR downloads) |
| `AGORA_DATA_EDGAR_USER_AGENT` | SEC-required User-Agent for EDGAR |
| `AGORA_DATA_CACHE_TTL_*` | Per-family cache TTLs |
| `AGORA_RESEARCH_*` | Default indicator periods (ATR 22, MA 50/200, RSI 14, and so on) |

### Saxo connections

Saxo SIM and LIVE are **separate developer apps** with their own app key/secret. The
connection's `environment` selects the endpoints:

- **Token endpoint:** `sim.logonvalidation.net` (SIM) vs `live.logonvalidation.net` (LIVE).
- **OpenAPI gateway:** `gateway.saxobank.com/sim/openapi` (SIM) vs `gateway.saxobank.com/openapi` (LIVE).

Each connection needs a **one-time OAuth login**: open
`GET /auth/saxo/login?connection=<name>` (e.g. `saxo-sim`), complete Saxo's login, and the
callback `/auth/saxo/callback` stores tokens under the token-dir
(`AGORA_TRADING_SAXO_TOKEN_DIR`, default `/data/saxo`). Tokens are auto-refreshed from
there, so the login persists across restarts.

Ambiguous cross-listed symbols must be disambiguated with a connection-level
`extra.exchange-id` (e.g. `NASDAQ`); otherwise resolution fails with
`ambiguous symbol: <symbol> — set extra.exchange-id`. The Saxo instrument resolver is
**stock-only** (`AssetTypes=Stock`) — no FX or other asset types.

---

## Running

### Docker (production image)

```bash
docker run --rm -p 8080:8080 \
  -e AGORA_AUTH_TOKENS=my-general-token \
  -e AGORA_DATA_FINNHUB_KEY=... \
  ghcr.io/visterion/agora:main
```

The image is built and pushed by CI on every push to `main`.

### Local build

The repo needs JDK 25. If your system `java`/`javac` is older, point Maven at JDK 25:

```bash
export JAVA_HOME=/usr/local/lib/jdk-25.0.2+10
export PATH=$JAVA_HOME/bin:$PATH

./mvnw test                       # run the full suite
./mvnw -Dtest=GetQuoteToolTest test   # single test
./mvnw -DskipTests package        # build the jar
```

### Smoke-testing tools

```bash
# discover the catalog
curl -s -H "Authorization: Bearer my-general-token" http://localhost:8080/tools | jq

# call a tool over the webhook
curl -s -X POST http://localhost:8080/tools/get_quote \
  -H "Authorization: Bearer my-general-token" \
  -H "Content-Type: application/json" \
  -d '{"symbols": ["AAPL", "MSFT"]}' | jq

# liveness (no token)
curl -s http://localhost:8080/actuator/health
```

---

## Adding a new tool

A new tool is one new class and no consumer changes.

1. Create a class in `de.visterion.agora.tools` implementing `AgoraTool`, annotated
   `@Component`.
2. Return a unique `name()`, a `description()`, and an `inputSchema()` (JSON Schema).
3. Implement `call(JsonNode args)` returning a `ToolResult` (`ToolResult.ok(output)` or
   `ToolResult.unavailable(reason)`).
4. For execution tools, override `namespace()` to return `"trading"`.
5. Add a test under `src/test/java/.../tools/`.

The `ToolRegistry` picks it up automatically and it appears on `/mcp`, `/tools`, and
`/tools/{name}` on the next start. No consumer rebuild.

---

## Tech stack

- **Java 25**, **Spring Boot 4**
- **Spring AI 2.0** MCP server (`spring-ai-starter-mcp-server-webmvc`), core SDK
  `io.modelcontextprotocol.sdk`
- Transport: **Streamable HTTP** (SSE is being sunset mid-2026)
- **ta4j** for technical indicators
- Apache HttpClient 5 for provider calls; WireMock for provider tests
- Packaged as a single Docker image (`ghcr.io/visterion/agora:main`)

---

## Disclaimer

Agora is **open-source software that you run yourself**, not a managed service. You
self-host your own instance and supply your own broker and data-provider API keys.

- **You run it; you own the data flow.** Agora ships code, not a data proxy. Market
  data and orders flow directly between *your* instance and the provider/broker APIs
  (Alpaca, Saxo, Finnhub, TwelveData, SEC EDGAR). The maintainers do not operate a
  server and do not redistribute any market data.
- **Your keys, your terms.** Because you configure your own API keys
  (`AGORA_TRADING_ALPACA_KEY_ID`, `AGORA_DATA_FINNHUB_KEY`, and so on), you use your own
  accounts and are bound by each provider's and broker's Terms of Service, including any
  Professional or Non-Professional market-data classification. That is strictly between
  you and them.
- **Some data sources are unofficial.** A few bundled providers, notably the keyless
  Yahoo Finance fallback, call **undocumented** endpoints that are not covered by a
  developer agreement. Prefer the keyed providers (Finnhub, TwelveData) for anything you
  depend on, and satisfy yourself that your use complies with each source's Terms of
  Service. The operator, not the maintainers, is responsible for that compliance.
- **Trade execution is at your own risk.** The `trading` tools place **real orders**
  on whatever account your keys point to. A bug, a bad agent decision, or a provider
  outage can cause financial loss. You are solely responsible for every order placed
  through your instance. Test against a paper account first; Alpaca paper is the default.
- **No affiliation.** Agora is an independent project. It is not affiliated with,
  endorsed by, or sponsored by Alpaca, Saxo Bank, Interactive Brokers, Yahoo, Finnhub,
  TwelveData, or any other provider whose API it can call. All trademarks belong to their
  respective owners.
- **No warranty.** To the extent permitted by applicable law, the software is provided
  "AS IS" (see the MIT license), without warranty of any kind. It is **not** financial,
  investment, legal, or tax advice.

---

## License

[MIT](LICENSE) © 2026 vivu
