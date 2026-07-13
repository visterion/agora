# Hunting grounds: data providers and coverage

Agora's data tools resolve through provider plugins with fallback, so consumers never pick a provider. This page documents which providers back each data domain, their coverage, and their guarantees.

## Market data and pricing

| Domain | Provider chain | Coverage | Cache TTL |
|---|---|---|---|
| Quotes, OHLC, intraday | Alpaca (broker feed, IEX), then Saxo (non-US via `saxo-live` session, Yahoo-suffix symbols), then TwelveData, Finnhub, then Yahoo (keyless fallback) | Equities globally, with 15-min delay on Saxo non-US | 120s (prices) |
| FX rates | Alpaca (broker pair rates), then Saxo FX | Major pairs and crosses | 120s |

## Company data

| Domain | Provider | Coverage | Semantics |
|---|---|---|---|
| Company profile (name, industry, exchange, cap) | Finnhub | Finnhub's covered universe | Real-time |
| Company news | Finnhub | Finnhub's covered universe | Last 100 headlines, real-time |
| Analyst estimates, recommendation trend | Finnhub | Finnhub's covered universe | Real-time |
| Earnings calendar (upcoming + recent) | Finnhub, Yahoo fallback | Finnhub coverage or global Yahoo fallback | Real-time |

## Fundamentals and SEC filings

| Domain | Provider | Coverage | Semantics | Notes |
|---|---|---|---|---|
| Company fundamentals — screener metrics | **US:** Finnhub / **Non-US (suffixed):** computed from SEC EDGAR + Yahoo concepts, OHLC, quote | US (Finnhub's universe), non-US (suffixed symbols like SAP.DE, 7203.T, 0700.HK) | Metrics in reporting currency (cap/P-B/P-E) or quote currency (price-relative). Config-gated by `agora.fundamentals.global-metrics-enabled` (default off); fails gracefully if data unavailable. | Accessed via `get_fundamentals` (global routing) |
| Company fundamentals — raw line items | **US:** SEC EDGAR (XBRL `us-gaap`) / **Non-US:** Yahoo `fundamentals-timeseries` | **US:** COMPLETE (all reported concepts) / **Non-US:** SPARSE (curated subset) | Reporting currency (may differ from listing currency) | Non-US is free, unofficial, and fail-soft; EODHD planned as future reliability upgrade |
| SEC filings (by symbol/CIK), filterable by form | SEC EDGAR | US-listed and foreign-filers (form 20-F, 20-F/A, 40-F) | Raw EDGAR archive | Public filings only; Form-4 available via separate tool |
| SEC filing full text (primary doc extraction) | SEC EDGAR | US-listed and foreign-filers | Text extraction, summary/term-sheet section detection, ~24k char limit, SSRF-guarded | Passthrough for non-EDGAR forms |
| XBRL company-concept (full reported history) | SEC EDGAR (XBRL `us-gaap`, `ifrs-full`, `invest` taxonomies) | US-listed, foreign-filers, mutual funds, investment companies | Per-concept values, units, datapoints | `get_company_concept` for one concept; `get_company_facts` for multiple in one fetch |
| Reported quarterly EPS | SEC EDGAR (XBRL `us-gaap/NetIncomeLoss`) | US-listed companies | Quarterly only | Via `get_eps_history` |
| Form-4 insider transactions | SEC EDGAR Form-4 filings, market-wide | US-listed equities | Non-derivative beneficial-ownership changes only | `get_form4_transactions` for date-window scan; `get_form4_owner_history` for multi-year per-owner history |
| Earnings calendar (events by date) | Finnhub, Yahoo fallback | Finnhub coverage or global fallback | Per-company reported earnings window (actual or estimate) | `get_earnings_calendar` per symbol; `get_earnings_window` market-wide by date |

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
| Saxo | `depot-1` (SIM/developer), `saxo-live` | Developer app OAuth, per-environment separate app credentials | Global equities, forex, indices, bonds | 15-min delayed on non-US via `saxo-live` session; fallback after Alpaca and TwelveData in pricing chain |

Both brokers support headless self-contained OAuth that refreshes without interactive login, required for Agora's deployment model.

---

## Notes

**Fail-soft data providers:** Yahoo fundamentals and some market-data fallbacks degrade to `unavailable` on error, never throwing exceptions.

**Reporting currency:** Concepts in the fundamentals response include a `unit` field that reflects the reporting currency (e.g., a Swiss company reports in CHF, a Hong Kong company in HKD). This may differ from the listing currency (a Hong Kong-listed company trading in HKD may report consolidated results in USD or CNY). Consumers must normalize currencies if needed.

**Future upgrades:** Non-US fundamentals are currently sourced via Yahoo's unofficial `fundamentals-timeseries` (free, SPARSE, fail-soft). A planned upgrade path exists via EODHD's official data feed for improved reliability and completeness on non-US markets.
