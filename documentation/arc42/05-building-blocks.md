# 05 — Building Block View

Top-level package: `de.visterion.agora`. Entry point: `AgoraApplication`.

## Level 1 — logical surfaces

| Surface | Packages | Responsibility |
|---|---|---|
| **Tool framework** | `tool`, `tools`, `mcp`, `web` | `AgoraTool` contract, `ToolRegistry` discovery, MCP adapter, HTTP catalog/webhook controllers. |
| **agora-data** | `data`, `fetch/*` | Read-only market data via ordered `MarketDataProvider` chain; fundamentals, filings, earnings, splits, reference/index data. |
| **agora-research** | `research`, `research/fundamentals` | Indicators on ta4j; YAML catalog; fundamental concepts/scores routing. |
| **agora-trading** | `trading`, `trading/saxo` | Execution via named `BrokerProvider`s; connection registry, live-access gating, probes. |
| **security** | `security` | `BearerTokenFilter` — auth for tool and trading scopes. |
| **observability** | `observability` | Provider-call logging with secret redaction. |

## Level 2 — key building blocks

- **`ToolRegistry` + `AgoraTool`** — every tool is a bean implementing `AgoraTool`; the
  registry discovers and exposes them to the MCP adapter and the catalog controller.
- **`MarketDataService` + `MarketDataProvider`** — ordered provider chain
  **Alpaca → Saxo → TwelveData → Finnhub → Yahoo**, with TTL cache and normalized errors.
- **`IndicatorService` + `IndicatorRegistry`** — indicators from `BuiltinIndicators` +
  `indicators-catalog.yaml`, resolved and computed via ta4j; composable via
  `IndicatorExpressionResolver`.
- **`FundamentalsRouter` + score services** — US → EDGAR concepts; non-US → Yahoo
  timeseries; Piotroski via `FundamentalScoreService`; optional global metrics shape.
- **`BrokerService` + `ConnectionRegistry` + `LiveAccessGuard`** — connection-scoped
  execution; startup probes report per-connection reachability; live connections require a
  stronger token.
- **Index change plugins** — `SpPressReleaseIndexChangeProvider`,
  `RussellReconstitutionIndexChangeProvider` under `fetch/reference/change/`.

## Tests

`src/test/java/de/visterion/agora` mirrors the main packages (tools, data, research,
trading, fetch, mcp, security, web); provider integration tests use WireMock.

Capability inventory: [`../capabilities.md`](../capabilities.md).
