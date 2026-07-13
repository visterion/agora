#!/bin/bash
# Global Metrics Feasibility Gate (Task 1)
# Tests: shares×price ≈ market cap, Finnhub scale, .L unit consistency, FX coverage, Yahoo annualTotalDebt
set -uo pipefail

# Extract token from agora-deploy/.env
T=$(grep -E '^AGORA_AUTH_TOKENS=' /root/agora-deploy/.env | cut -d= -f2- | tr -d ' "' | cut -d, -f1)
if [ -z "$T" ]; then
  echo "FAIL: Cannot read AGORA_AUTH_TOKENS from /root/agora-deploy/.env"
  exit 1
fi

call() {
  curl -s -m 30 -X POST "localhost:8091/tools/$1" \
    -H "Authorization: Bearer $T" \
    -H 'Content-Type: application/json' \
    -d "$2"
}

echo "================================================================"
echo "GATE 1: Finnhub scale (AAPL fundamentals)"
echo "================================================================"
echo "Expected: roaTTM/margins are PERCENTS (~25 not 0.25), marketCap MILLIONS, D/E+currentRatio are ratios"
call get_fundamentals '{"symbol":"AAPL"}' | python3 -c "
import sys, json
m = json.load(sys.stdin)['output']['metrics']
keys = ['roaTTM','grossMarginTTM','marketCapitalization','totalDebt/totalEquityQuarterly','currentRatioQuarterly']
print(json.dumps({k: m.get(k) for k in keys}, indent=2))
"

echo ""
echo "================================================================"
echo "GATE 2: Quote vs OHLC unit consistency (.L, DE, 7203.T, 0700.HK)"
echo "================================================================"
echo "Expected: VOD.L quote & ohlc.lastClose both ~1.15 GBP major (NOT ~115 pence); SAP.DE in EUR; 7203.T in JPY; 0700.HK in HKD"
for S in VOD.L SAP.DE 7203.T 0700.HK; do
  printf "%-9s " "$S"

  # get_quote
  printf "quote="
  call get_quote "{\"symbol\":\"$S\"}" | python3 -c "
import sys, json
d = json.load(sys.stdin)['output']
q = (d.get('quotes') or [d])[0] if isinstance(d.get('quotes'), list) else d
price = q.get('price')
currency = q.get('currency', '?')
print(f'{price:.4f} {currency}', end=' ')
"

  # get_ohlc lastClose
  printf "ohlc_lastClose="
  call get_ohlc "{\"symbol\":\"$S\",\"days\":5}" | python3 -c "
import sys, json
b = json.load(sys.stdin)['output']
bars = b.get('bars') or b
lc = bars[-1].get('close') if bars else None
print(f'{lc:.4f}' if lc else 'NONE', end='')
"

  echo ""
done

echo ""
echo "================================================================"
echo "GATE 3: FX rate coverage (HKD→CNY, GBP→EUR)"
echo "================================================================"
echo "Expected: both pairs return valid rates"
for P in HKD:CNY GBP:EUR; do
  FROM="${P%%:*}"
  TO="${P##*:}"
  printf "%-12s " "$P"
  call get_fx_rate "{\"from\":\"$FROM\",\"to\":\"$TO\"}" | python3 -c "
import sys, json
d = json.load(sys.stdin)['output']
rate = d.get('rate')
print(f'rate={rate}', end='')
"
  echo ""
done

echo ""
echo "================================================================"
echo "GATE 4: Yahoo annualTotalDebt (SAP.DE, 0700.HK)"
echo "================================================================"
echo "Expected: annualTotalDebt field present in timeseries response"

# Setup Yahoo crumb handshake
UA='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36'
CJ=$(mktemp)
trap "rm -f '$CJ'" EXIT

echo "Fetching Yahoo crumb..."
curl -s --retry 3 --retry-delay 2 -c "$CJ" -A "$UA" 'https://fc.yahoo.com' -o /dev/null || true
curl -s --retry 3 --retry-delay 2 -b "$CJ" -c "$CJ" -A "$UA" 'https://finance.yahoo.com/quote/AAPL' -o /dev/null || true
CRUMB=$(curl -s --retry 4 --retry-delay 3 -b "$CJ" -A "$UA" 'https://query1.finance.yahoo.com/v1/test/getcrumb')

if [ "${#CRUMB}" -lt 20 ]; then
  echo "WARNING: crumb handshake may be rate-limited (len=${#CRUMB})"
fi

yahoo_ts() {
  local SYMBOL="$1"
  local TYPES="$2"
  curl -s -b "$CJ" -A "$UA" \
    "https://query2.finance.yahoo.com/ws/fundamentals-timeseries/v1/finance/timeseries/$SYMBOL?symbol=$SYMBOL&type=$TYPES&merge=false&padTimeSeries=true&period1=1104537600&period2=1799999999&crumb=$CRUMB"
}

for S in SAP.DE 0700.HK; do
  printf "%-9s " "$S"
  yahoo_ts "$S" "annualTotalDebt" | python3 -c "
import sys, json
d = json.load(sys.stdin)
res = (d.get('timeseries', {}) or {}).get('result') or []
found = False
for x in res:
  if x.get('meta', {}).get('type', ['?'])[0] == 'annualTotalDebt':
    arr = [v for v in (x.get('annualTotalDebt') or []) if v]
    if arr:
      found = True
      val = arr[-1].get('reportedValue', {}).get('raw')
      print(f'annualTotalDebt={val}')
      break
if not found:
  print('annualTotalDebt=MISSING')
"
done

echo ""
echo "================================================================"
echo "GATE SUMMARY"
echo "================================================================"
