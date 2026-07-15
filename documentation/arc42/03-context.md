# 03 — Context and Scope

## System boundary

Agora is a standalone Spring Boot service. Consumers talk to it over MCP and HTTP webhooks;
Agora talks to external market-data and broker APIs over HTTPS.

```
   MCP / webhook consumer (e.g. Dracul, Vistierie)
            │  /mcp  ·  POST /tools/{name}  ·  GET /tools
            │  bearer token
            ▼
     ┌──────────────┐   HTTPS   ┌─────────────────────────────┐
     │    Agora     │──────────▶│ Market data: Alpaca, Saxo,  │
     │  (this repo) │           │ Twelve Data, Finnhub, Yahoo,│
     │              │           │ SEC EDGAR, Wikipedia,       │
     │              │           │ S&P press, FTSE Russell,    │
     │              │           │ iShares                     │
     │              │──────────▶│ Brokers: Alpaca, Saxo       │
     └──────────────┘           └─────────────────────────────┘
```

## External interfaces

| Interface | Direction | Purpose |
|---|---|---|
| MCP endpoint `/mcp` | inbound | Tool discovery and invocation (general tools only). |
| `POST /tools/{name}` | inbound | Webhook invocation (includes trading tools). |
| `GET /tools` | inbound | Tool catalog (trading filtered out). |
| `/actuator/health` | inbound | Liveness/readiness for operators. |
| `/auth/saxo/*` | inbound | Saxo OAuth callback / session for headless broker auth. |
| `BearerTokenFilter` | inbound | Bearer-token auth; trading and live scopes are separate. |
| Market-data provider APIs | outbound | Quotes, OHLC, intraday, fundamentals, filings, calendars. |
| Broker APIs | outbound | Account, positions, orders, bracket execution. |

## Out of scope

Domain shaping (spinoff/merger/earnings framing), verdicts, theses, and provenance are the
**consumer's** responsibility. Agora only passes through an opaque `client_ref`. See the
scope-discipline rule in the crosscutting concepts.

Full capability inventory: [`../capabilities.md`](../capabilities.md).
