# API Reference

This reference currently covers the fundamentals tools; other Agora tools are self-describing via their MCP inputSchema.

---

## Fundamentals and SEC filings

### `get_fundamentals`

Fundamental metrics (P/E, ROE, Debt/Equity, Free Cash Flow, etc.) for a symbol. **Note:** this is distinct from `get_fundamental_concepts` — use `get_fundamentals` for summary metrics via Finnhub (US) or computed from concepts+OHLC+quote (non-US), or `get_fundamental_concepts` for raw XBRL-backed line items from SEC EDGAR or Yahoo.

**Global routing** (gated by `agora.fundamentals.global-metrics-enabled`, default `false`):
- **US ticker:** Finnhub (unchanged behavior)
- **Non-US ticker (suffixed symbols):** computed from SEC EDGAR / Yahoo concepts, OHLC, and current price. Metrics like `marketCapitalization`, `pbAnnual`, `peTTM` are in reporting currency; price-relative metrics like `freeCashFlowPerShareTTM` are in quote currency. The non-US output carries `reportingCurrency` (the reporting-currency code that `marketCapitalization` and the other reporting-currency metrics are expressed in) so consumers can check currency consistency against `get_fundamental_concepts`. Fails gracefully (omits unavailable fields) if upstream data is missing.

**Input:**
```json
{ "symbol": "AAPL" }
```

**Output:**
```json
{
  "symbol": "AAPL",
  "pe": 28.5,
  "roe": 0.95,
  "debtToEquity": 1.23,
  "freeCashFlow": 95000000000
}
```

**Availability:** MCP, webhook, catalog

---

### `get_fundamental_concepts`

Raw normalized company-fundamentals line items (neutral accounting concepts + reporting currency). Routed globally: **US** via SEC EDGAR (`us-gaap` XBRL, COMPLETE semantics), **non-US** via Yahoo `fundamentals-timeseries` (SPARSE, free, unofficial, fail-soft).

**Input:**
```json
{ "symbol": "AAPL" }
```

**Output:**
```json
{
  "symbol": "AAPL",
  "source": "COMPLETE",
  "concepts": {
    "Assets": {
      "unit": "USD",
      "datapoints": [
        { "periodStart": null, "periodEnd": "2026-09-30", "value": 352000000000, "filed": "2026-11-01" },
        { "periodStart": null, "periodEnd": "2026-06-30", "value": 346000000000, "filed": "2026-08-02" }
      ]
    },
    "Liabilities": {
      "unit": "USD",
      "datapoints": [
        { "periodStart": null, "periodEnd": "2026-09-30", "value": 120000000000, "filed": "2026-11-01" }
      ]
    }
  }
}
```

**Source values:**
- `COMPLETE`: all reported XBRL concepts available (US via SEC EDGAR)
- `SPARSE`: curated subset of concepts, free/unofficial source (non-US via Yahoo)

**Output notes:**
- `unit` is the reporting currency (may differ from listing currency; e.g., a company trading in HKD may report in CNY or USD)
- each datapoint carries `periodStart` (null for instant/balance-sheet facts, set for duration facts), `periodEnd`, `value`, and `filed` (the filing date, null if unknown) — consumers use `periodStart` to tell instant vs duration facts apart and `filed` for restatement dedup
- datapoint order is not guaranteed and differs by source; sort by `periodEnd` if you need a specific order
- Empty concepts are omitted (only concepts with data are included)
- an unknown symbol (NOT_FOUND) returns `available:true` with an empty `concepts` object — "ran fine, no data" — not an error envelope; a genuine source outage returns `available:false`

**Availability:** MCP, webhook, catalog

---

### `get_fundamental_score`

Standardized fundamental-health scores (Piotroski F-score, 0–9). **Global routing:** US symbols via SEC EDGAR (all 9 criteria, strict evaluation); non-US symbols via Yahoo fundamentals (0–9 criteria depending on data availability).

**Input:**
```json
{ "symbol": "AAPL" }
```

**Output:**
```json
{
  "symbol": "AAPL",
  "scores": {
    "piotroskiF": {
      "score": 8,
      "criteriaAvailable": 9,
      "criteria": {
        "roa": { "met": true, "available": true },
        "operatingCashFlow": { "met": true, "available": true },
        "netIncome": { "met": true, "available": true },
        "accrualRatio": { "met": true, "available": true },
        "currentRatio": { "met": true, "available": true },
        "grossMargin": { "met": false, "available": true },
        "assetTurnover": { "met": true, "available": true },
        "longTermDebt": { "met": true, "available": true },
        "workingCapital": { "met": true, "available": true }
      },
      "raw": {
        "roa": 0.29,
        "cfo": 120000000000,
        "netIncome": 95000000000,
        "accrualRatio": 0.05,
        "currentRatio": 1.53,
        "grossMargin": 0.45,
        "assetTurnover": 0.65,
        "longTermDebt": 100000000000,
        "workingCapital": 60000000000
      }
    }
  }
}
```

**Output notes:**
- `score`: 0–9, computed from met criteria
- `criteriaAvailable`: 0–9, number of criteria for which data was available (a criterion only counts as available if it can be strictly evaluated; met criteria require verifiable evidence, otherwise score 0)
- `criteria.<name>.met`: whether the criterion was met (always false if `available` is false)
- `raw`: underlying figures used for score computation, in reporting currency
- an unknown symbol (NOT_FOUND) returns `available:true` with `score:null` and empty criteria — not an error; a genuine source outage returns `available:false`

**Availability:** MCP, webhook, catalog

---

### `get_company_facts`

Multiple XBRL `us-gaap` concepts for one company in a single fetch (cheaper than N `get_company_concept` calls).

**Input:**
```json
{ "symbol": "AAPL", "tags": ["Assets", "Liabilities", "Revenue"] }
```

**Output:**
```json
{
  "symbol": "AAPL",
  "facts": {
    "Assets": {
      "unit": "USD",
      "datapoints": [ ... ]
    },
    "Liabilities": {
      "unit": "USD",
      "datapoints": [ ... ]
    },
    "Revenue": {
      "unit": "USD",
      "datapoints": [ ... ]
    }
  }
}
```

**Output notes:**
- an unknown symbol (NOT_FOUND) returns `available:true` with empty `datapoints` per requested tag (`cik:null`) — not an error; a genuine source outage returns `available:false`

**Availability:** MCP, webhook, catalog

---

## Market data

### `get_company_news`

Company news headlines for a symbol, merged from multiple sources: Finnhub plus every
RSS/Atom feed configured under `agora.data.news.feeds` (default: Yahoo RSS and two Reddit
searches tagged `social`). Providers are fetched in parallel under a fixed total budget;
a failing or slow provider degrades to a `warnings` entry instead of failing the call.

**Input**

| Field | Type | Required | Description |
|---|---|---|---|
| `symbol` | string | yes | ticker symbol |
| `from` | string | no | start date ISO (YYYY-MM-DD); default 7 days ago |
| `to` | string | no | end date ISO (YYYY-MM-DD); default today |
| `sourceTypes` | string[] | no | filter by media type: `news` and/or `social`; case-insensitive; unknown values are ignored with a warning; empty/omitted = all. Applied before the item cap. |

**Output**

```json
{ "symbol": "AAPL",
  "news": [ { "headline": "...", "summary": "...", "source": "yahoo-rss",
              "sourceType": "news", "datetime": "2026-07-16T20:35:00Z", "url": "..." } ],
  "warnings": ["rss:reddit-stocks: timeout"] }
```

- `sourceType` is a media-type label: `news` (editorial/wire) or `social` (user-generated).
- `datetime` is `null` when the source carried no parseable timestamp; such items sort last.
- `warnings` is omitted when empty; one sanitized entry per degraded provider.
- Items are deduplicated (normalized URL first, then normalized title; first source wins),
  sorted by `datetime` descending, and capped at `agora.data.news.max-items` (default 200).
- The call is `unavailable` only when every configured provider fails.

**Availability:** MCP, webhook, catalog

---

## Trading / execution

### `get_positions`

All open positions held by the account on the named connection.

**Input:**
```json
{
  "connection": "alpaca-paper"
}
```

**Output:**
```json
{
  "positions": [
    {
      "symbol": "AAPL",
      "description": "Apple Inc.",
      "qty": 100,
      "avgEntryPrice": 150.25,
      "marketPrice": 155.30,
      "marketValue": 15530.00,
      "unrealizedPl": 505.00,
      "currency": "USD",
      "assetType": "US_EQUITY",
      "valueDate": "2026-07-16",
      "openOrdersCount": 1
    }
  ],
  "asOf": "2026-07-16T14:30:00Z"
}
```

**Response fields:**
- `symbol`: ticker symbol
- `description`: asset description
- `qty`: quantity held
- `avgEntryPrice`: average entry price per unit
- `marketPrice`: current per-unit market price; may be `null` (e.g., when qty is 0). Saxo derives this from `marketValue/qty` because the SIM feed reports `CurrentPrice=0`; Alpaca maps native `current_price`.
- `marketValue`: total market value of the position
- `unrealizedPl`: unrealized profit or loss
- `currency`: currency code
- `assetType`: asset type (e.g., `US_EQUITY`)
- `valueDate`: valuation date
- `openOrdersCount`: number of open orders for this instrument. Saxo: `NetPositionBase.OpenOrdersCount`; Alpaca: always `0` (endpoint carries no count).
- `asOf`: ISO-8601 instant when the data was fetched

**Availability:** webhook only (requires trading token)
