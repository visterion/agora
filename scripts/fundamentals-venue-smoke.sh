#!/bin/bash

# Post-deploy smoke test for fundamental concepts and scores across venues
# Run against prod host port 8091 after deploy to CT 102
#
# Expected outcomes:
# - get_fundamental_concepts: AAPL unchanged from before; SAP.DE/7203.T/0700.HK return non-empty
#   concepts with source:"SPARSE" and correct unit (EUR/JPY/CNY)
# - get_fundamental_score: AAPL unchanged; non-US returns real score with criteriaAvailable > 0

set -e

# Extract the first token from AGORA_AUTH_TOKENS
TOKEN=$(grep -E '^AGORA_AUTH_TOKENS=' /root/agora-deploy/.env | cut -d= -f2- | tr -d ' "' | cut -d, -f1)

if [ -z "$TOKEN" ]; then
  echo "ERROR: Could not extract AGORA_AUTH_TOKENS from /root/agora-deploy/.env"
  exit 1
fi

# Helper function to call a tool
q() {
  local tool=$1
  local symbol=$2
  curl -s -m 30 -X POST http://localhost:8091/tools/$tool \
    -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' \
    -d "{\"symbol\":\"$symbol\"}"
  echo "  <- $tool $symbol"
}

echo "== get_fundamental_concepts =="
for S in AAPL SAP.DE 7203.T 0700.HK; do
  q get_fundamental_concepts "$S"
done

echo ""
echo "== get_fundamental_score (US unchanged + non-US now populated) =="
for S in AAPL SAP.DE 0700.HK; do
  q get_fundamental_score "$S"
done

echo ""
echo "Smoke test complete."
