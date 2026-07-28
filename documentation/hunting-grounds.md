# Hunting grounds: data providers and coverage

Agora's data tools resolve through provider plugins, so consumers never pick a provider themselves. Most domains resolve via a fallback chain (first success wins); earnings is the exception — it merges every provider that can see the requested window instead of stopping at the first one that answers (see "Earnings calendar merge" below). This page documents which providers back each data domain, their coverage, and their guarantees.

## Market data and pricing

| Domain | Provider chain | Coverage | Cache TTL |
|---|---|---|---|
| Quotes | Alpaca (broker feed, IEX), then Saxo (non-US via `saxo-live` session, Yahoo-suffix symbols), then TwelveData, Finnhub, then Yahoo (keyless fallback) | Equities globally, with 15-min delay on Saxo non-US | 300s (`agora.data.cache.ttl.quote-seconds`, `AGORA_DATA_CACHE_TTL_QUOTE`) — split from OHLC to cut repeat provider calls for watchlist/kill-criteria/depot quotes |
| OHLC, intraday | Alpaca (broker feed, IEX), then Saxo (non-US via `saxo-live` session, Yahoo-suffix symbols), then TwelveData, Finnhub, then Yahoo (keyless fallback) | Equities globally, with 15-min delay on Saxo non-US | 120s (`agora.data.cache.ttl-seconds`, `AGORA_DATA_CACHE_TTL_SECONDS`) — kept short so GUI charts never show a stale "today" partial bar |
| FX rates | Yahoo FX pairs (optional scheduled warmer) | Major pairs and crosses | 120s |

## Company data

| Domain | Provider | Coverage | Semantics |
|---|---|---|---|
| Company profile (name, industry, exchange, cap) | Finnhub (US); Yahoo `quoteSummary` `assetProfile` fallback for non-US suffixed symbols | Finnhub's covered universe (US), global for non-US via Yahoo | Real-time (US); non-US cached 7d (`agora.data.cache.ttl.company-profile-seconds`, `AGORA_DATA_CACHE_TTL_COMPANY_PROFILE`) — degrades to an empty (non-null) profile, uncached, on Yahoo outage |
| Company news | Finnhub (US) + region-agnostic RSS feeds; Finnhub skipped for non-US suffixed symbols (empty result, RSS providers still serve foreign news) | Finnhub's covered universe (news), global (RSS) | Last 100 headlines, real-time |
| Analyst estimates, recommendation trend | Finnhub (US); Yahoo `quoteSummary` `recommendationTrend` fallback for non-US suffixed symbols | Finnhub's covered universe (US), global for non-US via Yahoo | Real-time (US); non-US cached 1d (`agora.data.cache.ttl.recommendation-seconds`, `AGORA_DATA_CACHE_TTL_RECOMMENDATION`) — degrades to an empty list, uncached, on Yahoo outage |
| Earnings calendar (upcoming + recent) | Finnhub + Nasdaq (future-only) merged in parallel, Yahoo as a coverage-gap filler | Finnhub coverage plus Nasdaq's forecast-only calendar; Yahoo only for what both left uncovered | Complete answers cached the standard TTL; partial answers cached briefly, see "Earnings calendar merge" below |

### Earnings calendar merge

`get_earnings_calendar` and `get_earnings_window` no longer take the first non-empty
answer from a fallback chain — every provider that can see the requested window is
queried in parallel under one shared budget (`agora.fetch.earnings.budget-ms`, default
9000ms; each provider attempt is separately capped by
`agora.fetch.earnings.attempt-timeout-ms`, default 2500ms) and their results are merged.

Those two values are not independent knobs. A worst-case attempt is the Finnhub limiter
wait **plus** the 3000ms connect timeout **plus** the read timeout, and the sum has to fit
*strictly* inside the budget: otherwise a hanging provider can only ever be
budget-cancelled, and a budget cancellation deliberately does not trip the cooldown, so it
is re-attempted on every request forever. The earnings limiter wait is therefore **derived**
from the other two rather than configured separately (`budget − connect − attempt − 500ms
margin`, i.e. 3000ms at the defaults); a combination that leaves no usable wait fails
startup with an explicit message instead of silently voiding the guarantee.

- **Finnhub** — primary source, full history (past actuals + future estimates).
- **Nasdaq** — key-less, day-granular (one HTTP call per calendar day, cached per day
  so a wide window is paid for once and shared across every symbol and overlapping
  window). It declares itself **future-only**: it has no `epsActual`, so past days are
  never considered "answered" by Nasdaq alone. Its day-by-day fetch is capped at
  `agora.data.nasdaq.day-cap` (default 95) — **this must stay ≥ 91**, because
  `get_earnings_calendar` defaults to a `now+90` window, and a lower cap would make the
  tool's most common call permanently partial. The loop is also **time-aware**: it stops
  before starting a day whose worst case would outrun the shared budget. Either stop —
  day cap or budget — is reported with the data and makes the merged answer `partial`, so
  a truncated window is short-TTL cached and retried rather than passing as complete. On a
  cold cache a wide window therefore converges over successive calls (each call keeps the
  days it reached in the shared day cache) instead of being cancelled and starting over.
- **Yahoo** — consulted only for symbols the above two left uncovered, and only from an
  asynchronously warmed page cache, never crawled inline (the crawl can take longer than
  the merge budget). **Yahoo's calendar index is currently broken server-side**: every
  window and user-agent combination tried returns HTTP 500. The provider is deliberately
  left wired in behind a failure cooldown (`agora.fetch.earnings.cooldown-threshold`,
  default 3 consecutive failures; `agora.fetch.earnings.cooldown-ms`, default 600000) so
  that if Yahoo's index comes back, Agora starts using it again automatically — no code
  change or redeploy required.

**Three-valued outcome, not a fallback chain.** An empty result from a provider that
could see the requested window is a valid, cacheable answer ("no earnings scheduled"),
not an error — the previous first-success chain treated an empty result the same as "no
answer" and fell through, which turned a correct empty answer into an `unavailable` the
moment the next provider in the chain was down. Today, the result additionally carries a
`partial` flag: `partial: true` means a provider that was actually needed for this
window failed, was cooled down, ran out of budget, or could only cover part of the window,
so the returned events cannot be trusted as the complete picture. A **cooled** provider
counts here exactly like a failing one — being deliberately skipped is still "we could not
see it this call", and an empty result must never be reported as a definitive "no earnings
scheduled" on data Agora could not see. Complete answers are cached for the standard
fundamentals TTL; partial ones for a short TTL (`agora.fetch.earnings.partial-ttl-seconds`,
default 600s) — long enough to stop hammering a failing provider, short enough not to
poison a session once it recovers. The call only throws `unavailable` when nothing
usable answered at all (no provider covers the window, or every provider that does
failed).

## Fundamentals and SEC filings

| Domain | Provider | Coverage | Semantics | Notes |
|---|---|---|---|---|
| Company fundamentals — screener metrics | **US:** Finnhub / **Non-US (suffixed):** computed from SEC EDGAR + Yahoo concepts, OHLC, quote | US (Finnhub's universe), non-US (suffixed symbols like SAP.DE, 7203.T, 0700.HK) | Metrics in reporting currency (cap/P-B/P-E) or quote currency (price-relative). Config-gated by `agora.fundamentals.global-metrics-enabled` (default off); fails gracefully if data unavailable. | Accessed via `get_fundamentals` (global routing) |
| Company fundamentals — raw line items (`get_fundamental_concepts`) | **US:** SEC EDGAR (XBRL `us-gaap`) / **Non-US:** Yahoo `fundamentals-timeseries` | **US:** COMPLETE (all reported concepts) / **Non-US:** SPARSE (curated subset) | Reporting currency (may differ from listing currency) | Non-US is free, unofficial, and fail-soft; EODHD planned as future reliability upgrade |
| SEC filings (by symbol/CIK), filterable by form | SEC EDGAR | US-listed and foreign-filers (form 20-F, 20-F/A, 40-F) | Raw EDGAR archive | Public filings only; Form-4 available via separate tool |
| SEC filing full text (primary doc extraction) | SEC EDGAR | US-listed and foreign-filers | Text extraction, summary/term-sheet section detection, ~24k char limit, SSRF-guarded | Passthrough for non-EDGAR forms |
| XBRL company-concept (full reported history) | SEC EDGAR (XBRL `us-gaap`, `ifrs-full`, `invest` taxonomies) | US-listed, foreign-filers, mutual funds, investment companies | Per-concept values, units, datapoints | `get_company_concept` for one concept; `get_company_facts` for multiple in one fetch |
| Reported quarterly EPS | SEC EDGAR (XBRL `us-gaap/NetIncomeLoss`) | US-listed companies | Quarterly only | Via `get_eps_history` |
| Form-4 insider transactions | SEC EDGAR Form-4 filings, market-wide | US-listed equities | Non-derivative beneficial-ownership changes only | `get_form4_transactions` for date-window scan; `get_form4_owner_history` for multi-year per-owner history |
| Earnings calendar (events by date) | Finnhub + Nasdaq merged, Yahoo for uncovered gaps | Finnhub coverage plus Nasdaq's forecast-only calendar; Yahoo fills what's left | Per-company reported earnings window (actual or estimate); see "Earnings calendar merge" below for the completeness rules | `get_earnings_calendar` per symbol; `get_earnings_window` market-wide by date |

### Fundamental scoring (Piotroski F-score)

| Domain | Provider | Coverage | Routing |
|---|---|---|---|
| Piotroski F-score (fundamental health) | SEC EDGAR (US) or Yahoo (non-US) | Globally routed | **US ticker:** SEC EDGAR XBRL `us-gaap` concepts (COMPLETE, all 9 criteria, strict evaluation). **Non-US ticker:** Yahoo `fundamentals-timeseries` (SPARSE, free/unofficial, fail-soft, 0–9 criteria depending on data availability). Fails gracefully (`unavailable` on missing upstream data or routing errors). |

Concept units reflect the reporting currency (not listing currency), so a company reporting in EUR or JPY will reflect those `unit` values in the response.

## Index data

| Domain | Provider | Coverage | Notes |
|---|---|---|---|
| Index constituents (default S&P 500) | Wikipedia (S&P 500), FTSE Russell PDFs + iShares holdings (Russell 1000/2000) | S&P 500, Russell 1000, Russell 2000 | Cached 24h |
| Index constituent changes (pending + recent) | S&P Dow Jones Indices press-release RSS (S&P 500) or FTSE Russell reconstitution PDFs + iShares IWB/IWM buckets (Russell 1000/2000) | S&P 500 adds/removes via press release; Russell 1000/2000 add/remove via PDF + iShares holdings resolver | S&P press-release ordered first; Russell ordered after |

---

## Brokers (execution, trading tools only)

| Broker | Environments | Auth | Coverage | Notes |
|---|---|---|---|---|
| Alpaca | `alpaca-paper` (default), `alpaca-live` | Headless API key/secret | US and some international equities | IEX market data included; broker-fed quotes are first in the data provider chain |
| Saxo | `depot-1` (SIM/developer), `saxo-live` | Developer app OAuth, per-environment separate app credentials | Global equities, forex, indices, bonds | 15-min delayed on non-US via `saxo-live` session; second in pricing chain after Alpaca (before TwelveData, Finnhub, Yahoo) |

Both brokers support headless self-contained OAuth that refreshes without interactive login, required for Agora's deployment model.

---

## Notes

**Fail-soft data providers:** Yahoo fundamentals and some market-data fallbacks degrade to `unavailable` on error, never throwing exceptions.

**Reporting currency:** Concepts in the fundamentals response include a `unit` field that reflects the reporting currency (e.g., a Swiss company reports in CHF, a Hong Kong company in HKD). This may differ from the listing currency (a Hong Kong-listed company trading in HKD may report consolidated results in USD or CNY). Consumers must normalize currencies if needed.

**Future upgrades:** Non-US fundamentals are currently sourced via Yahoo's unofficial `fundamentals-timeseries` (free, SPARSE, fail-soft). A planned upgrade path exists via EODHD's official data feed for improved reliability and completeness on non-US markets.
