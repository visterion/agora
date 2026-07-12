# 05 — Building Block View

> [!NOTE]
> **AI-Generated** — inferred from the package structure; needs human review.

Top-level package: `de.visterion.agora`. Entry point: `AgoraApplication`.

## Level 1 — logical surfaces

| Surface | Packages | Responsibility |
|---|---|---|
| **Tool framework** | `tool`, `tools`, `mcp`, `web` | `AgoraTool` contract, `ToolRegistry` discovery, MCP adapter, HTTP catalog/webhook controllers. |
| **agora-data** | `data`, `fetch/*` | Read-only market data via ordered `MarketDataProvider` chain; fundamentals, filings, earnings, splits, reference data. |
| **agora-research** | `research` | Pure quant/indicator evaluation on ta4j; indicator registry + YAML catalog. |
| **agora-trading** | `trading`, `trading/saxo` | Execution via ordered `BrokerProvider`s; connection registry, live-access gating, probes. |
| **security** | `security` | `BearerTokenFilter` — auth for tool and trading scopes. |

## Level 2 — key building blocks

- **`ToolRegistry` + `AgoraTool`** — every tool is a bean implementing `AgoraTool`; the
  registry discovers and exposes them to the MCP adapter and the catalog controller.
- **`MarketDataService` + `MarketDataProvider`** — an ordered provider chain (Finnhub,
  Twelve Data, Yahoo, Alpaca, Saxo) with a TTL cache and normalized errors.
- **`IndicatorService` + `IndicatorRegistry`** — indicators defined in
  `indicators-catalog.yaml`, resolved and computed via ta4j.
- **`BrokerService` + `ConnectionRegistry` + `LiveAccessGuard`** — connection-scoped
  execution; startup probes report per-connection reachability; live connections require a
  stronger token.

## Tests

`src/test/java/de/visterion/agora` mirrors the main packages (tools, data, research,
trading, fetch, mcp, security, web); provider integration tests use WireMock.
