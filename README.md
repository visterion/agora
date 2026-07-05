# Agora

> **A broker- and provider-agnostic MCP tool suite for market data, quant research, and
> trade execution. Register a tool once in Agora and every consumer can use it, with no
> consumer rebuild.**

[![docker](https://github.com/visterion/agora/actions/workflows/docker.yml/badge.svg)](https://github.com/visterion/agora/actions/workflows/docker.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/java-25-blue)](https://openjdk.org)
[![Spring Boot](https://img.shields.io/badge/spring%20boot-4.0-6DB33F)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/spring%20ai-2.0-6DB33F)](https://spring.io/projects/spring-ai)
[![MCP](https://img.shields.io/badge/MCP-Streamable%20HTTP-6E56CF)](https://modelcontextprotocol.io)

**Docker image:** [`ghcr.io/visterion/agora:main`](https://github.com/visterion/agora/pkgs/container/agora)

---

Agora is a standalone service that exposes a catalog of financial tools (quotes, OHLC,
technical indicators, fundamentals, SEC filings, earnings calendars, and broker execution)
over the [Model Context Protocol](https://modelcontextprotocol.io) (MCP) and a plain HTTP
webhook. Consumers such as AI agents and trading systems call these tools without knowing
which data provider or broker sits behind them.

It is built as a single Spring Boot 4 / JDK 25 application and ships as one Docker image.

---

## Why Agora exists

Agora is one product in a family of sibling services:

| Service | Role |
|---|---|
| **Vistierie** | Infrastructure / agent platform |
| **HiveMem** | Long-term memory |
| **Agora** | Market data, quant, and execution (**this repo**) |
| **Dracul** | A *consumer*: an investment-research agent that uses Agora |

The guiding principle (the **Nordstern**):

> A new tool, provider, broker, or quant building block is registered **in Agora**.
> The consumer stays untouched, and no consumer rebuild is required.

Dracul, and any future consumer, gains capabilities purely by Agora adding a tool, never
by changing consumer code. That is why Agora is a separate service rather than a library
baked into each agent.

### Scope discipline: the hard boundary

Agora is **generic**. It owns raw market data, execution, and neutral quant math. It must
**never** contain investment domain vocabulary.

| Agora owns | The consumer (e.g. Dracul) owns |
|---|---|
| Raw market data, execution, pure quant | Domain shaping (spinoffs, mergers, earnings theses) |
| Plugin registry, MCP transport, auth, health | Verdicts, decisions, provenance |
| Normalized, broker-/provider-neutral domain (Account/Order/Position/Fill) | Anomaly screening, framing, prompts, orchestration |

**Rule:** if a word like `spinoff`, `insider`, `PEAD`, `verdict`, or `thesis` appears in an
Agora tool name, schema, or code, that is a layer-violation bug. Investment provenance
lives only in the consumer, which passes an opaque `client_ref` that Agora merely echoes.

The **two-consumer rule** keeps Agora honest: once a second consumer exists alongside
Dracul, it validates that the API is truly neutral.

---

## Architecture

Agora runs as one Spring Boot application. A single tool registry is served through two
front-doors, the MCP endpoint and an HTTP webhook, and both expose the identical set of
tools.

```
                       ┌──────────────────────────────────────────┐
                       │                 Agora                     │
   MCP client ───────▶ │  /mcp   (Streamable HTTP, MCP protocol)   │
   (Claude, agents)    │      └────┐                               │
                       │           ▼                               │
   Vistierie agent ──▶ │  /tools/{name} (POST)  ──▶ ToolRegistry ──┼──▶ provider plugins
   (webhook)           │  /tools        (GET, catalog)   │         │    (Yahoo, Finnhub,
                       │                                 ▼         │     TwelveData, EDGAR,
   ops / k8s ────────▶ │  /actuator/health (public)   AgoraTool   │     Wikipedia)
                       │                              beans        │
                       │                                 │         │
                       │                                 ▼         │
                       │                          broker plugins   │
                       │                          (Alpaca, IBKR)   │
                       └──────────────────────────────────────────┘
```

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
tool on **both** front-doors at once:

- `McpToolAdapter` turns each tool into an MCP `SyncToolSpecification` on `/mcp`.
- `ToolWebhookController` serves each tool at `POST /tools/{name}` (Vistierie-compatible).
- `ToolCatalogController` mirrors the catalog at `GET /tools` for discovery.

Both the MCP adapter and the catalog **filter out `trading`-namespace tools**, so execution
is never exposed over MCP or the public catalog. It is reachable only over the authenticated
webhook.

### Logical grouping

Although it is one binary, the code is organized into the three logical servers from the
design spec:

| Group | Package(s) | Content |
|---|---|---|
| **agora-data** | `data`, `fetch/*` | Read-only market data: quotes, OHLC, intraday, FX, news, fundamentals, filings, earnings, index constituents. Provider plugins. |
| **agora-research** | `research` | Pure quant/analysis: ATR, Chandelier, MA-cross, RSI, MACD, Bollinger, ADX, CCI, Stochastic, Williams %R, OBV, 52-week range, R-framework. Built on [ta4j](https://github.com/ta4j/ta4j). |
| **agora-trading** ⚠ | `tools` (namespace `trading`) | Execution: account, positions, orders, bracket place/modify, flatten. Broker plugins (Alpaca now, IBKR later). |

---

## Tool catalog

38 tools today. `general` tools are on MCP, webhook, and catalog; `trading` tools are
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
| `get_index_constituents` | Constituents of a stock index (default S&P 500) |

### Fundamentals & SEC filings (`agora-data`)

| Tool | Description |
|---|---|
| `get_filings` | Recent SEC filings (by symbol or CIK), optionally filtered by form |
| `search_filings` | SEC EDGAR full-text search by form type(s) and date window |
| `get_company_concept` | Full reported history of any XBRL company-concept (e.g. `us-gaap/Assets`) |
| `get_eps_history` | Reported quarterly EPS history (by symbol or CIK) |
| `get_form4_transactions` | Non-derivative SEC Form-4 transactions (beneficial-ownership changes) |
| `get_earnings_calendar` | Recent and upcoming earnings events for a symbol |
| `get_earnings_window` | Market-wide earnings events reported in a date window (one row per company) |

### Research / technical indicators (`agora-research`)

| Tool | Description |
|---|---|
| `get_indicators` | Bundled indicators (ATR, Chandelier stop, MA cross, 52-week) in one call |
| `get_atr` | Average True Range (volatility) |
| `get_chandelier_stop` | Chandelier Exit stop-loss level (`breached=true` when price is below) |
| `get_ma_cross` | Moving-average cross state: BULLISH / BEARISH |
| `get_52w_range` | 52-week high and low |
| `get_rsi` | Relative Strength Index |
| `get_macd` | Moving Average Convergence Divergence |
| `get_bollinger` | Bollinger Bands (upper/middle/lower) |
| `get_stochastic` | Stochastic Oscillator (%K / %D) |
| `get_adx` | Average Directional Index |
| `get_cci` | Commodity Channel Index |
| `get_williams_r` | Williams %R |
| `get_obv` | On-Balance Volume |
| `get_r_framework` | Risk unit and R-multiple price levels |
| `ping` | Liveness probe that returns `pong` plus any echoed message |

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

---

## Providers & brokers (plugins)

Data tools resolve through provider plugins with fallback; the consumer never picks a
provider.

| Domain | Plugins |
|---|---|
| Quotes / OHLC / intraday | TwelveData, Finnhub, then keyless Yahoo Finance as last-resort fallback |
| Company profile / news / fundamentals / estimates | Finnhub |
| Filings / XBRL concepts / EPS / Form-4 | SEC EDGAR |
| Earnings calendar | Finnhub, Yahoo |
| Index constituents | Wikipedia (S&P 500) |
| Execution | Alpaca (paper by default); IBKR planned |

Responses are cached with per-family TTLs (prices 120s, news 15m, fundamentals 6h,
filings 1h, constituents 24h), all configurable.

---

## Security

Guarded by `BearerTokenFilter` on `/tools/**` and `/mcp/**`:

- **General tools and `/mcp`**: accept either a general or a trading token.
- **Trading tools** (`/tools/{name}` where `namespace()=="trading"`): accept **only**
  trading tokens.
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
| `AGORA_TRADING_TOKENS` | Comma-separated execution bearer tokens |
| `AGORA_TRADING_PROVIDER` | Broker plugin (default `alpaca`) |
| `AGORA_TRADING_ALPACA_KEY_ID` / `_SECRET` / `_BASE_URL` | Alpaca credentials (defaults to paper API) |
| `AGORA_DATA_FINNHUB_KEY` | Finnhub API key |
| `AGORA_DATA_TWELVEDATA_KEY` | TwelveData API key |
| `AGORA_DATA_EDGAR_USER_AGENT` | SEC-required User-Agent for EDGAR |
| `AGORA_DATA_CACHE_TTL_*` | Per-family cache TTLs |
| `AGORA_RESEARCH_*` | Default indicator periods (ATR 22, MA 50/200, RSI 14, and so on) |

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

Adding a tool takes one new class and no consumer changes.

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
  (Alpaca, IBKR, Finnhub, TwelveData, SEC EDGAR). The maintainers do not operate a
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
  endorsed by, or sponsored by Alpaca, Interactive Brokers, Yahoo, Finnhub, TwelveData,
  or any other provider whose API it can call. All trademarks belong to their respective
  owners.
- **No warranty.** To the extent permitted by applicable law, the software is provided
  "AS IS" (see the MIT license), without warranty of any kind. It is **not** financial,
  investment, legal, or tax advice.

---

## License

[MIT](LICENSE) © 2026 vivu
