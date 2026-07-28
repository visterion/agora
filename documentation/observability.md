# Observability: provider-call logging

Every outbound HTTP call Agora makes to an upstream data or broker provider (Yahoo,
Finnhub, SEC EDGAR, Saxo, Alpaca, TwelveData, Wikipedia) is logged as one structured,
redacted line. This gives operators a single log-based source of truth for "what did
Agora actually call, and how did it respond" without needing to add ad-hoc logging per
adapter.

## Configuration

| Property | Env var | Default | Purpose |
|---|---|---|---|
| `agora.provider-logging.enabled` | `AGORA_PROVIDER_LOGGING_ENABLED` | `true` | Turn provider-call logging on/off entirely. |
| `agora.provider-logging.max-body-chars` | `AGORA_PROVIDER_LOGGING_MAX_BODY_CHARS` | `4096` | Caps how many characters of the (redacted) request/response body are logged per call; the remainder is summarized as a byte count. |

To silence provider-call logging without a redeploy (e.g. during a noisy incident),
raise the logger's level instead of touching config:

```
logging.level.agora.providercall=WARN
```

The logger name is `agora.providercall`; at `INFO` (the default) every provider call is
logged, at `WARN` only logging failures are.

## Log line format

Each call emits one `INFO`-level line with this exact field order:

```
provider_call provider={} method={} host={} path={} query={} headers={} status={} dur_ms={} symbol={} req_bytes={} resp_bytes={} req_body={} resp_body={}
```

Example (a Yahoo chart request, redacted):

```
provider_call provider=yahoo method=GET host=query2.finance.yahoo.com path=/v8/finance/chart/AAPL query=crumb=***&interval=1d status=200 dur_ms=142 symbol=AAPL req_bytes=0 resp_bytes=18432 req_body=- resp_body={"chart":{"result":[{"meta":{"currency":"USD","symbol":"AAPL"…[+18190b]
```

Notes on the fields:
- `provider` is derived from the request host (`yahoo`, `finnhub`, `edgar`, `saxo`,
  `alpaca`, `twelvedata`, `wikipedia`, or the raw host if unrecognized).
- `symbol` is best-effort, parsed from the path (`/chart/{symbol}`) or a `symbol=`
  query parameter; `-` if none is found.
- `status` is `ERR` if the call failed before a status code was received.
- Bodies are capped at `max-body-chars`; anything beyond that is replaced with
  `…[+Nb]` showing the number of additional bytes omitted.

## Analyzing a run

Count provider calls by provider and status from a container's logs, e.g. after a
manual Strigoi sweep or to check adapter usage during an incident:

```
docker logs agora | grep provider_call \
  | sed -E 's/.*provider=([^ ]+).*status=([^ ]+).*/\1 \2/' | sort | uniq -c | sort -rn
```

This prints one line per `(provider, status)` pair with a count, sorted descending —
useful for spotting an unexpected fallback chain (e.g. Yahoo being hit far more than
expected because Saxo is failing) or a provider returning non-200 statuses repeatedly.

## News provider rate-limit summary logging

This is separate from the `agora.providercall` logging above: it is a per-provider
business-level summary, not a per-HTTP-call structured line, and is logged under the
`NewsAggregator` class logger instead.

When a news provider rejects a symbol lookup because it is in a rate-limit cooldown
(after an earlier real 429), that individual skip is logged at `DEBUG` only — one WARN
per skipped symbol would flood the log during a run over many symbols. Instead,
`NewsAggregator` accumulates skips per provider and emits one `WARN`-level summary line,
`news provider {id} rate-limited (cooldown): skipped {N} symbol lookup(s) ...`, whichever
of two triggers fires first:

- a 5-minute window elapses since the first skip in the current window, or
- the accumulated skip count for that provider reaches **20** (the burst threshold) —
  this is what makes a large burst inside a single bounded batch run (e.g. a Strigoi
  hunt over many symbols that starts and ends well within 5 minutes) self-report instead
  of silently finishing with zero WARNs.

An isolated skip or two never crosses either threshold and stays at `DEBUG` only.
Client-visible tool output (`AggregatedNews.warnings`) still carries the per-call
warning regardless of this summary; only the server-log verbosity is affected.

## Redaction guarantee

Provider-call log lines never contain secrets or PII. Redaction happens before the line
is built, not by post-filtering:

- **Headers:** `Authorization`, `X-Finnhub-Token`, `APCA-API-KEY-ID`, and
  `APCA-API-SECRET-KEY` values are replaced with `***`. Any header value starting with
  `Bearer `, `Basic `, or `apikey ` is also masked, regardless of header name.
- **Query parameters:** `token`, `crumb`, and `apikey` (case-insensitive key match) are
  replaced with `key=***`; all other query parameters are left as-is.
- **Body fields:** JSON and form-encoded occurrences of `refresh_token`, `client_secret`,
  `password`, `token`, and `crumb` are masked in both request and response bodies.
- **EDGAR User-Agent email:** any email address embedded in the EDGAR `User-Agent`
  header (required by SEC EDGAR's fair-access policy) is masked to `***@***`.

This redaction is implemented in `ProviderLogRedactor` and is pure/stateless — it has no
dependency on Spring context, so it is unit-testable in isolation from the logging
interceptor itself.
