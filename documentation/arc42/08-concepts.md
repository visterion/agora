# 08 — Crosscutting Concepts

## Plugin extension model

Data and broker sources are ordered plugins (`MarketDataProvider`, `BrokerProvider`),
tried in `@Order` sequence. Tools are beans implementing `AgoraTool`, discovered by
`ToolRegistry`. Adding a capability means registering a new bean/provider — no consumer
change, no rebuild of consumers.

## Scope discipline (neutrality boundary)

Agora is generic. Investment vocabulary (`spinoff`, `insider`, `PEAD`, `verdict`,
`thesis`, …) must not appear in Agora tool names, schemas, or code — that would be a
layer-violation bug. Consumers own domain shaping and provenance; Agora passes through an
opaque `client_ref`. A second consumer alongside Dracul keeps this API honest.

## Authentication & execution safety

`BearerTokenFilter` authenticates requests. The trading namespace has its own token scope
separate from general tool access. Trading calls select a `connection` explicitly; live
connections require a stronger (full) token for mutating operations, while reads may accept
a read-only token. Live connections are invisible to non-live tokens — in discovery and in
error messages alike — to avoid an enumeration oracle.

## Error handling & resilience

Provider errors are normalized (`ProviderErrors`) so consumers see consistent failures
across sources. Market-data reads use a TTL cache. Broker connections are probed at startup
and on a periodic tick; a transient failure reports `pending` and self-heals, while
`unreachable` signals a genuine auth/liveness problem.

## Configuration

Behavior is driven by `application.yaml` and `indicators-catalog.yaml`; secrets and
provider keys come from the environment. Operational/deployment specifics are kept in
private runbooks outside this public documentation.
