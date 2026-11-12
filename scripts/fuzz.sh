#!/usr/bin/env bash
# Contract fuzzing with Schemathesis (build doc section 12): generated requests against
# api/openapi.yaml, once per role, so every operation is exercised by a caller allowed to call it.
# Fails on any 5xx, any response that does not match the spec, or any undocumented status code.
#
#   scripts/fuzz.sh [base_url]          defaults to http://localhost:8080 with local credentials
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
SCHEMATHESIS_VERSION="${SCHEMATHESIS_VERSION:-4.32.4}"
MAX_EXAMPLES="${FUZZ_MAX_EXAMPLES:-30}"
OPS_ID="${OPS_CLIENT_ID:-cli_ops_local}"
OPS_SECRET="${OPS_CLIENT_SECRET:-local-ops-secret}"
SVC_ID="${SERVICE_CLIENT_ID:-cli_service_local}"
SVC_SECRET="${SERVICE_CLIENT_SECRET:-local-service-secret}"
REPORT_DIR="${FUZZ_REPORT_DIR:-fuzz-report}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

token() {
  curl -fsS "$BASE_URL/v1/auth/token" -H 'Content-Type: application/json' \
    -d "{\"client_id\":\"$1\",\"client_secret\":\"$2\"}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])'
}

OPS_TOKEN="$(token "$OPS_ID" "$OPS_SECRET")"
SVC_TOKEN="$(token "$SVC_ID" "$SVC_SECRET")"
creds="$(curl -fsS "$BASE_URL/v1/customers" -H "Authorization: Bearer $OPS_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"email\":\"fuzz-$(date +%s)-$RANDOM@example.test\",\"name\":\"Fuzz Customer\"}")"
CUST_ID="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["credentials"]["client_id"])' "$creds")"
CUST_SECRET="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["credentials"]["client_secret"])' "$creds")"
CUST_TOKEN="$(token "$CUST_ID" "$CUST_SECRET")"

mkdir -p "$REPORT_DIR"
status=0
for role in ops service customer; do
  case "$role" in
    ops) t="$OPS_TOKEN" ;; service) t="$SVC_TOKEN" ;; customer) t="$CUST_TOKEN" ;;
  esac
  echo "=== fuzzing as $role"
  uvx --quiet "schemathesis@$SCHEMATHESIS_VERSION" --config-file "$ROOT/schemathesis.toml" run "$ROOT/api/openapi.yaml" \
    --url "$BASE_URL" \
    --header "Authorization: Bearer $t" \
    --max-examples "$MAX_EXAMPLES" \
    --seed 20261111 \
    --report junit --report-junit-path "$REPORT_DIR/$role.xml" \
    || status=1
done
exit $status
