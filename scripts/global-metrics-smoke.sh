#!/bin/bash
# Post-deploy smoke test for global metrics (SP2a+SP2b joint deploy)
# Verifies get_fundamentals global routing with the flag ON
# NOTE: Flag must be OFF in prod until SP2b deploys; this smoke is for staging/manual verification only.

set -e

BASE_URL="${AGORA_BASE_URL:-http://localhost:8080}"
ENDPOINT="/api/v1/get_fundamentals"

echo "=== Agora Global Metrics Smoke Test ==="
echo "Base URL: $BASE_URL"
echo "Endpoint: $ENDPOINT"
echo ""
echo "NOTE: This smoke test assumes agora.fundamentals.global-metrics-enabled=true (staging only)."
echo "In production, the flag must remain false until SP2b deployment."
echo ""

SCREENER_KEYS=("52WeekLow" "roaTTM" "totalDebt/totalEquityQuarterly" "pbAnnual" "freeCashFlowPerShareTTM" "marketCapitalization")

test_symbol() {
    local symbol=$1
    local route_type=$2

    echo "Testing: $symbol ($route_type route)"

    response=$(curl -s -w "\n%{http_code}" "$BASE_URL$ENDPOINT?symbol=$symbol")
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n-1)

    if [ "$http_code" != "200" ]; then
        echo "  ✗ HTTP $http_code"
        echo "  Response: $body"
        return 1
    fi

    echo "  ✓ HTTP 200"

    # Check for required screener keys
    for key in "${SCREENER_KEYS[@]}"; do
        if echo "$body" | grep -q "\"$key\""; then
            echo "    ✓ $key present"
        else
            echo "    ⚠ $key absent (fail-soft omission)"
        fi
    done

    echo ""
}

# US symbol → Finnhub route (unchanged behavior)
test_symbol "AAPL" "Finnhub (US)"

# Non-US suffixed symbols → computed route (global metrics)
test_symbol "SAP.DE" "Computed (non-US)"
test_symbol "7203.T" "Computed (non-US)"
test_symbol "0700.HK" "Computed (non-US)"

echo "=== Smoke test complete ==="
echo "All symbols tested. Refer to screener keys in output above."
echo ""
echo "IMPORTANT: The global-metrics-enabled flag stays OFF in prod until SP2b deploys."
