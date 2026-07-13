#!/bin/bash
# Read-only Yahoo fundamentals-timeseries verification. No key needed (crumb handshake).
set -uo pipefail
UA='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36'
CJ=$(mktemp)
curl -s --retry 3 --retry-delay 2 -c "$CJ" -A "$UA" 'https://fc.yahoo.com' -o /dev/null || true
curl -s --retry 3 --retry-delay 2 -b "$CJ" -c "$CJ" -A "$UA" 'https://finance.yahoo.com/quote/AAPL' -o /dev/null || true
CRUMB=$(curl -s --retry 4 --retry-delay 3 -b "$CJ" -A "$UA" 'https://query1.finance.yahoo.com/v1/test/getcrumb')
echo "crumb len=${#CRUMB}"
[ "${#CRUMB}" -gt 20 ] && { echo "FAIL: crumb handshake rate-limited"; exit 1; }
ts() { curl -s -b "$CJ" -A "$UA" "https://query2.finance.yahoo.com/ws/fundamentals-timeseries/v1/finance/timeseries/$1?symbol=$1&type=$2&merge=false&padTimeSeries=true&period1=1104537600&period2=1799999999&crumb=$CRUMB"; }
for S in AAPL SAP.DE 7203.T 0700.HK VOD.L; do
  echo "### $S"
  ts "$S" annualTotalAssets,annualCurrentAssets,annualCurrentLiabilities,annualLongTermDebt,annualRetainedEarnings,annualEBIT,annualTotalRevenue,annualGrossProfit,annualNetIncome,annualOperatingCashFlow,annualOrdinarySharesNumber \
   | python3 -c 'import sys,json
d=json.load(sys.stdin); res=(d.get("timeseries",{}) or {}).get("result") or []
seen={}
for x in res:
  t=x.get("meta",{}).get("type",["?"])[0]
  arr=[v for v in (x.get(t) or []) if v]
  if arr:
    ccy=set(v.get("currencyCode") for v in arr)
    seen[t]=(arr[-1].get("reportedValue",{}).get("raw"), sorted(c for c in ccy if c))
for k in ("annualTotalAssets","annualRetainedEarnings","annualEBIT","annualLongTermDebt","annualOrdinarySharesNumber"):
  print("   ",k,"->",seen.get(k,"MISSING"))'
done
echo "### ISIN search DE0007164600"
curl -s -b "$CJ" -A "$UA" "https://query2.finance.yahoo.com/v1/finance/search?q=DE0007164600&crumb=$CRUMB" \
 | python3 -c 'import sys,json; q=json.load(sys.stdin).get("quotes") or []; print("   ->",[x.get("symbol") for x in q[:3]])'
rm -f "$CJ"
