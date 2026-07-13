# API Reference: Fundamentals Tools

This reference currently covers the fundamentals tools; other Agora tools are self-describing via their MCP inputSchema.

---

## Fundamentals and SEC filings

### `get_fundamentals`

Fundamental metrics (P/E, ROE, Debt/Equity, Free Cash Flow, etc.) for a symbol. **Note:** this is distinct from `get_fundamental_concepts` — use `get_fundamentals` for summary metrics via Finnhub (US) or computed from concepts+OHLC+quote (non-US), or `get_fundamental_concepts` for raw XBRL-backed line items from SEC EDGAR or Yahoo.

**Global routing** (gated by `agora.fundamentals.global-metrics-enabled`, default `false`):
- **US ticker:** Finnhub (unchanged behavior)
- **Non-US ticker (suffixed symbols):** computed from SEC EDGAR / Yahoo concepts, OHLC, and current price. Metrics like `marketCapitalization`, `pbAnnual`, `peTTM` are in reporting currency; price-relative metrics like `freeCashFlowPerShareTTM` are in quote currency. Fails gracefully (omits unavailable fields) if upstream data is missing.

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
        { "periodEnd": "2026-09-30", "value": 352000000000 },
        { "periodEnd": "2026-06-30", "value": 346000000000 }
      ]
    },
    "Liabilities": {
      "unit": "USD",
      "datapoints": [
        { "periodEnd": "2026-09-30", "value": 120000000000 }
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
- datapoint order is not guaranteed and differs by source; sort by `periodEnd` if you need a specific order
- Empty concepts are omitted (only concepts with data are included)

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
- Returns `unavailable` on upstream errors or insufficient data

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

**Availability:** MCP, webhook, catalog
