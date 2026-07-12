# 03 — Context and Scope

> [!NOTE]
> **AI-Generated** — inferred from code analysis; needs human review.

## System boundary

Agora is a standalone Spring Boot service. Consumers talk to it over MCP; Agora talks to
external market-data and broker APIs over HTTPS.

```
   MCP consumer (e.g. Dracul)
            │  MCP / Streamable HTTP  (bearer token)
            ▼
     ┌──────────────┐   HTTPS   ┌─────────────────────────────┐
     │    Agora     │──────────▶│ Market data: Finnhub,       │
     │  (this repo) │           │ Twelve Data, Yahoo, Alpaca, │
     │              │           │ Saxo, SEC EDGAR, Wikipedia, │
     │              │           │ S&P press, FTSE Russell,    │
     │              │           │ iShares                     │
     │              │──────────▶│ Brokers: Alpaca, Saxo       │
     └──────────────┘           └─────────────────────────────┘
```

## External interfaces

| Interface | Direction | Purpose |
|---|---|---|
| MCP endpoint `/mcp` | inbound | Tool discovery and invocation. |
| `/actuator/health` | inbound | Liveness/readiness for operators. |
| `BearerTokenFilter` | inbound | Bearer-token auth; trading has a separate token scope. |
| Market-data provider APIs | outbound | Quotes, OHLC, intraday, fundamentals, filings, calendars. |
| Broker APIs | outbound | Account, positions, orders, bracket execution. |

## Out of scope

Domain shaping (spinoff/merger/earnings framing), verdicts, theses, and provenance are the
**consumer's** responsibility. Agora only passes through an opaque `client_ref`. See the
scope-discipline rule in the crosscutting concepts.
