# API Reference: Tool catalog

Agora exposes 37 tools over MCP and HTTP. General tools (read/quant) are available on both MCP and the webhook endpoint; trading tools (execution, account) are webhook-only and require a trading token.

---

## Market data: quotes & technical data

### `get_quote`

Current price, day-change percent, and volume for one or more symbols.

**Input:**
```json
{ "symbols": ["AAPL", "MSFT", ...] }
```

**Output:**
```json
{
  "quotes": [
    {
      "symbol": "AAPL",
      "price": 150.25,
      "change": 2.5,
      "changePercent": 1.69,
      "volume": 45000000,
      "asOf": "2026-07-13T16:00:00Z"
    }
  ]
}
```

**Availability:** MCP, webhook, catalog

---

### `get_ohlc`

Daily OHLCV history, oldest-first, optionally limited to the last N bars.

**Input:**
```json
{ "symbol": "AAPL", "limit": 252 }
```

**Output:**
```json
{
  "symbol": "AAPL",
  "ohlc": [
    { "date": "2026-01-01", "open": 140.0, "high": 142.5, "low": 139.5, "close": 141.0, "volume": 40000000 }
  ]
}
```

**Availability:** MCP, webhook, catalog

---

### `get_intraday`

Intraday OHLCV candles at a specified interval (1m, 5m, 15m, 30m, 1h) over a time range.

**Input:**
```json
{ "symbol": "AAPL", "interval": "15m", "from": "2026-07-13T09:30:00Z", "to": "2026-07-13T16:00:00Z" }
```

**Output:**
```json
{
  "symbol": "AAPL",
  "interval": "15m",
  "candles": [
    { "timestamp": "2026-07-13T09:30:00Z", "open": 150.0, "high": 151.5, "low": 149.8, "close": 150.5, "volume": 500000 }
  ]
}
```

**Availability:** MCP, webhook, catalog

---

### `get_fx_rate`

Current FX conversion rate (1 unit of source currency in target currency).

**Input:**
```json
{ "from": "USD", "to": "EUR" }
```

**Output:**
```json
{ "from": "USD", "to": "EUR", "rate": 0.92, "asOf": "2026-07-13T16:00:00Z" }
```

**Availability:** MCP, webhook, catalog

---

## Company data

### `get_company_profile`

Company name, industry, exchange, market cap, and metadata.

**Input:**
```json
{ "symbol": "AAPL" }
```

**Output:**
```json
{
  "symbol": "AAPL",
  "name": "Apple Inc.",
  "exchange": "NASDAQ",
  "industry": "Consumer Electronics",
  "marketCap": 2800000000000
}
```

**Availability:** MCP, webhook, catalog

---

### `get_company_news`

Recent company news headlines (last 100), newest-first.

**Input:**
```json
{ "symbol": "AAPL" }
```

**Output:**
```json
{
  "symbol": "AAPL",
  "news": [
    {
      "headline": "Apple announces Q3 earnings",
      "source": "Reuters",
      "url": "https://...",
      "timestamp": "2026-07-12T14:30:00Z"
    }
  ]
}
```

**Availability:** MCP, webhook, catalog

---

### `get_analyst_estimates`

Analyst recommendation trend and target price estimates.

**Input:**
```json
{ "symbol": "AAPL" }
```

**Output:**
```json
{
  "symbol": "AAPL",
  "recommendation": "strong_buy",
  "targetPrice": 165.0,
  "buyCount": 30,
  "holdCount": 5,
  "sellCount": 2
}
```

**Availability:** MCP, webhook, catalog

---

### `get_earnings_estimates`

Reported EPS vs. analyst estimates per quarter, with raw surprise delta (actual − estimate).

**Input:**
```json
{ "symbol": "AAPL" }
```

**Output:**
```json
{
  "symbol": "AAPL",
  "estimates": [
    {
      "quarter": "Q3 2026",
      "actual": 1.25,
      "estimate": 1.20,
      "surprise": 0.05
    }
  ]
}
```

**Availability:** MCP, webhook, catalog

---

## Fundamentals and SEC filings

### `get_fundamentals`

Fundamental metrics (P/E, ROE, Debt/Equity, Free Cash Flow, etc.) for a symbol. **Note:** this is distinct from `get_fundamental_concepts` — use `get_fundamentals` for summary metrics via Finnhub, or `get_fundamental_concepts` for raw XBRL-backed line items from SEC EDGAR or Yahoo.

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
- `datapoints` are oldest-first; `periodEnd` is ISO-8601 date
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

### `get_company_concept`

Full reported history of any XBRL company-concept (e.g., `us-gaap/Assets`, `ifrs-full/Revenue`).

**Input:**
```json
{ "symbol": "AAPL", "concept": "us-gaap/Assets" }
```

**Output:**
```json
{
  "symbol": "AAPL",
  "concept": "us-gaap/Assets",
  "datapoints": [
    { "end": "2025-12-31", "value": 350000000000, "filed": "2026-01-30", "accession": "0000320193-26-000010" }
  ]
}
```

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

---

### `get_eps_history`

Reported quarterly EPS history for a company.

**Input:**
```json
{ "symbol": "AAPL" }
```

**Output:**
```json
{
  "symbol": "AAPL",
  "history": [
    { "quarter": "2026-Q1", "eps": 1.95 },
    { "quarter": "2026-Q2", "eps": 2.05 }
  ]
}
```

**Availability:** MCP, webhook, catalog

---

### `get_filings`

Recent SEC filings for a symbol or CIK, optionally filtered by form type (10-K, 10-Q, 8-K, 20-F, etc.).

**Input:**
```json
{ "symbol": "AAPL", "form": "10-K", "limit": 5 }
```

**Output:**
```json
{
  "filings": [
    {
      "symbol": "AAPL",
      "form": "10-K",
      "filedDate": "2026-01-30",
      "accession": "0000320193-26-000010",
      "url": "https://www.sec.gov/..."
    }
  ]
}
```

**Availability:** MCP, webhook, catalog

---

### `search_filings`

Full-text search across SEC EDGAR filings by form type(s) and date window.

**Input:**
```json
{ "forms": ["10-K", "10-Q"], "from": "2025-01-01", "to": "2026-07-13", "query": "material weakness" }
```

**Output:**
```json
{
  "results": [
    {
      "symbol": "ACME",
      "form": "10-K",
      "filedDate": "2026-01-15",
      "url": "https://www.sec.gov/..."
    }
  ]
}
```

**Availability:** MCP, webhook, catalog

---

### `get_filing_text`

Fetch a SEC filing's primary document as cleaned text. Extracts the summary/term-sheet section when present (fallback: leading text window). Truncated to ~24k chars.

**Input:**
```json
{ "url": "https://www.sec.gov/cgi-bin/viewer?action=view&cik=320193&accession_number=0000320193-26-000010&..." }
```

**Output:**
```json
{
  "text": "...",
  "section_found": true,
  "truncated": false,
  "char_count": 18500,
  "source_url": "https://www.sec.gov/..."
}
```

**Availability:** MCP, webhook, catalog

---

### `get_form4_transactions`

Non-derivative SEC Form-4 transactions (beneficial-ownership changes) filed market-wide in a date window.

**Input:**
```json
{ "from": "2026-07-01", "to": "2026-07-13", "limit": 1000 }
```

**Output:**
```json
{
  "transactions": [
    {
      "ticker": "AAPL",
      "filerName": "John Doe",
      "filerRole": "Director",
      "filerCik": "0000123456",
      "transactionDate": "2026-07-10",
      "code": "P",
      "acquiredDisposedCode": "A",
      "form": "4",
      "shares": 1000,
      "price": 150.00,
      "dollarValue": 150000,
      "sharesOwnedFollowing": 50000,
      "aff10b5One": true
    }
  ]
}
```

**Availability:** MCP, webhook, catalog

---

### `get_form4_owner_history`

Multi-year Form-4 transaction history for ONE company, grouped per reporting owner.

**Input:**
```json
{ "symbol": "AAPL", "years": 3 }
```

**Output:**
```json
{
  "symbol": "AAPL",
  "owners": [
    {
      "name": "John Doe",
      "cik": "0000123456",
      "role": "Director",
      "transactions": [
        {
          "transactionDate": "2026-07-10",
          "code": "P",
          "acquiredDisposedCode": "A",
          "form": "4",
          "shares": 1000,
          "price": 150.00,
          "dollarValue": 150000,
          "sharesOwnedFollowing": 50000,
          "aff10b5One": true
        }
      ]
    }
  ],
  "truncated": false
}
```

**Availability:** MCP, webhook, catalog

---

### `get_earnings_calendar`

Recent and upcoming earnings events for a symbol.

**Input:**
```json
{ "symbol": "AAPL" }
```

**Output:**
```json
{
  "symbol": "AAPL",
  "events": [
    {
      "date": "2026-10-15",
      "estimatedEps": 2.10,
      "reportedEps": null,
      "status": "upcoming"
    }
  ]
}
```

**Availability:** MCP, webhook, catalog

---

### `get_earnings_window`

Market-wide earnings events reported in a date window (one row per company).

**Input:**
```json
{ "from": "2026-07-01", "to": "2026-07-13", "limit": 100 }
```

**Output:**
```json
{
  "events": [
    {
      "symbol": "AAPL",
      "date": "2026-07-10",
      "estimatedEps": 2.10,
      "reportedEps": 2.05,
      "surprise": -0.05
    }
  ]
}
```

**Availability:** MCP, webhook, catalog

---

### `get_index_constituents`

Constituents of a stock index (default S&P 500).

**Input:**
```json
{ "index": "SP500" }
```

**Output:**
```json
{
  "index": "SP500",
  "constituents": ["AAPL", "MSFT", "AMZN", ...]
}
```

**Availability:** MCP, webhook, catalog

---

### `get_index_constituent_changes`

Pending and recent constituent changes for a stock index (add/remove, announcement + effective dates). Aggregates ordered providers.

**Input:**
```json
{ "index": "SP500" }
```

**Output:**
```json
{
  "index": "SP500",
  "changes": [
    {
      "symbol": "ACME",
      "action": "add",
      "announcementDate": "2026-07-10",
      "effectiveDate": "2026-07-20"
    }
  ]
}
```

**Availability:** MCP, webhook, catalog

---

## Research & technical indicators

### `list_indicators`

Machine-readable catalog of all 26 built-in indicators: names, params with defaults, outputs.

**Input:** (none)

**Output:**
```json
{
  "indicators": [
    {
      "name": "sma",
      "description": "Simple Moving Average",
      "params": {
        "period": { "type": "integer", "default": 20, "min": 1 }
      },
      "outputs": ["value"]
    },
    ...
  ]
}
```

**Availability:** MCP, webhook, catalog

---

### `get_indicators`

Computes any set of catalog indicators in one call. Composable specs allow chaining (e.g., RSI-of-SMA).

**Input:**
```json
{
  "symbol": "AAPL",
  "specs": [
    {
      "name": "sma",
      "params": { "period": 20 },
      "label": "SMA_20"
    },
    {
      "name": "rsi",
      "params": { "period": 14 },
      "series": 3
    }
  ]
}
```

**Output:**
```json
{
  "symbol": "AAPL",
  "results": {
    "SMA_20": 150.25,
    "rsi": [148.5, 149.0, 150.25]
  }
}
```

**Availability:** MCP, webhook, catalog

---

### `get_r_framework`

Risk unit and R-multiple price levels for position sizing.

**Input:**
```json
{ "symbol": "AAPL", "entry": 150.00, "stop": 148.00, "target": 155.00 }
```

**Output:**
```json
{
  "symbol": "AAPL",
  "riskUnit": 2.00,
  "rMultiple": 2.5,
  "levels": {
    "entry": 150.00,
    "stop": 148.00,
    "1r": 152.00,
    "2r": 154.00,
    "target": 155.00
  }
}
```

**Availability:** MCP, webhook, catalog

---

### `ping`

Liveness probe. Echoes any message back plus a `pong` status.

**Input:**
```json
{ "message": "hello" }
```

**Output:**
```json
{ "status": "pong", "echo": "hello" }
```

**Availability:** MCP, webhook, catalog

---

## Trading / execution (webhook only, trading token required)

### `get_account`

Account summary: equity, buying power, cash, status.

**Input:**
```json
{ "connection": "alpaca-paper" }
```

**Output:**
```json
{
  "connection": "alpaca-paper",
  "equity": 100000.00,
  "buyingPower": 400000.00,
  "cash": 50000.00,
  "status": "active"
}
```

**Availability:** Webhook only (trading token required)

---

### `get_positions`

All open positions for a connection.

**Input:**
```json
{ "connection": "alpaca-paper" }
```

**Output:**
```json
{
  "connection": "alpaca-paper",
  "positions": [
    {
      "symbol": "AAPL",
      "qty": 100,
      "avgPrice": 150.00,
      "currentPrice": 150.50,
      "unrealizedPL": 50.00
    }
  ]
}
```

**Availability:** Webhook only (trading token required)

---

### `get_orders`

All open and recent orders for a connection.

**Input:**
```json
{ "connection": "alpaca-paper" }
```

**Output:**
```json
{
  "connection": "alpaca-paper",
  "orders": [
    {
      "brokerOrderId": "...",
      "clientRef": "...",
      "symbol": "AAPL",
      "side": "buy",
      "qty": 100,
      "type": "limit",
      "status": "pending",
      "role": "entry",
      "filledQty": 50,
      "avgFillPrice": 150.00,
      "parentId": null
    }
  ]
}
```

**Availability:** Webhook only (trading token required)

---

### `get_order_by_ref`

Look up a single order by client reference ID.

**Input:**
```json
{ "connection": "alpaca-paper", "client_ref": "my-ref-123" }
```

**Output:**
```json
{
  "order": {
    "brokerOrderId": "...",
    "clientRef": "my-ref-123",
    "symbol": "AAPL",
    "status": "filled"
  }
}
```

**Availability:** Webhook only (trading token required)

---

### `place_bracket`

Place a bracket order (entry + stop-loss + take-profit) on a connection.

**Input:**
```json
{
  "connection": "alpaca-paper",
  "symbol": "AAPL",
  "side": "buy",
  "qty": 100,
  "entry": 150.00,
  "stop": 148.00,
  "target": 155.00,
  "client_ref": "my-bracket-1"
}
```

**Output:**
```json
{
  "accepted": true,
  "orderId": "parent-order-id",
  "clientRef": "my-bracket-1",
  "status": "pending",
  "stopLegId": "stop-leg-id",
  "takeProfitLegId": "tp-leg-id"
}
```

**Availability:** Webhook only (trading token required)

---

### `modify_bracket`

Modify the stop-loss and/or take-profit of an existing bracket.

**Input:**
```json
{
  "connection": "alpaca-paper",
  "orderId": "parent-order-id",
  "symbol": "AAPL",
  "stop": 147.00,
  "target": 156.00
}
```

**Output:**
```json
{
  "accepted": true,
  "orderId": "parent-order-id",
  "status": "modified"
}
```

**Availability:** Webhook only (trading token required)

---

### `flatten`

Close (flatten) the entire position for a symbol via market order, or partially close by fraction/qty.

**Input:**
```json
{
  "connection": "alpaca-paper",
  "symbol": "AAPL",
  "fraction": 0.5
}
```

**Output:**
```json
{
  "accepted": true,
  "orderId": "close-order-id",
  "status": "filled",
  "closedQty": 50,
  "remainingQty": 50,
  "avgFillPrice": 150.25
}
```

**Availability:** Webhook only (trading token required)

---

### `cancel_order`

Cancel an open order by broker order id.

**Input:**
```json
{
  "connection": "alpaca-paper",
  "orderId": "..."
}
```

**Output:**
```json
{
  "accepted": true,
  "orderId": "...",
  "status": "canceled"
}
```

**Availability:** Webhook only (trading token required)

---

### `list_connections`

List active trading connections visible to the caller (id, provider, environment, probe status).

**Input:** (none)

**Output:**
```json
{
  "connections": [
    {
      "id": "alpaca-paper",
      "provider": "Alpaca",
      "environment": "paper",
      "status": "connected",
      "lastProbeTime": "2026-07-13T16:00:00Z"
    }
  ]
}
```

**Availability:** Webhook only (trading token required; live-readonly tokens can list and read-only on live connections)

---

## Availability legend

| Label | Meaning |
|---|---|
| **MCP, webhook, catalog** | Available on MCP endpoint, HTTP webhook, and GET `/tools` catalog |
| **Webhook only** | HTTP webhook only; not exposed on MCP or catalog |

All tool requests require authentication. General tools accept either a general or trading token. Trading tools (execution) require a trading token specifically. Live connections (`alpaca-live`, `saxo-live`) require a live-access token (a separate, disjoint set).
