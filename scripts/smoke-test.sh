#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

NGINX_BASE_URL="${NGINX_BASE_URL:-http://localhost:8080}"
JAVA_BASE_URL="${JAVA_BASE_URL:-http://localhost:18080}"
AGENT_BASE_URL="${AGENT_BASE_URL:-http://localhost:18000}"
OCR_BASE_URL="${OCR_BASE_URL:-http://localhost:18010}"
SMOKE_USER_ID="${SMOKE_USER_ID:-smoke-user}"
SMOKE_MERCHANT_ID="${SMOKE_MERCHANT_ID:-smoke-merchant}"
RESPONSE_FILE="$(mktemp)"
trap 'rm -f "${RESPONSE_FILE}"' EXIT

assert_http_200() {
  local name="$1"
  local url="$2"
  local status
  status="$(curl --silent --show-error --max-time 30 \
    --output "${RESPONSE_FILE}" --write-out '%{http_code}' "${url}")"
  if [[ "${status}" != "200" ]]; then
    echo "${name} failed with HTTP ${status}" >&2
    cat "${RESPONSE_FILE}" >&2
    exit 1
  fi
  echo "${name}: PASS"
}

assert_http_200 "nginx" "${NGINX_BASE_URL}/healthz"
assert_http_200 "frontend-through-nginx" "${NGINX_BASE_URL}/"
assert_http_200 "java-api-service" "${JAVA_BASE_URL}/actuator/health"
assert_http_200 "python-agent-service" "${AGENT_BASE_URL}/health"
assert_http_200 "ocr-parser-service" "${OCR_BASE_URL}/health"

for service in $(docker compose config --services); do
  container_id="$(docker compose ps -q "${service}")"
  if [[ -z "${container_id}" ]]; then
    echo "${service}: container is missing" >&2
    exit 1
  fi
  state="$(docker inspect --format '{{.State.Status}}' "${container_id}")"
  health="$(docker inspect --format \
    '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' \
    "${container_id}")"
  if [[ "${state}" != "running" || "${health}" != "healthy" ]]; then
    echo "${service}: state=${state}, health=${health}" >&2
    docker compose logs --tail 80 "${service}" >&2
    exit 1
  fi
  echo "${service}: healthy"
done

idempotency_key="smoke-$(date +%s)-$$"
case_status="$(curl --silent --show-error --max-time 180 \
  --output "${RESPONSE_FILE}" --write-out '%{http_code}' \
  -X POST "${NGINX_BASE_URL}/api/v1/cases" \
  -H 'Content-Type: application/json' \
  -H "X-User-Id: ${SMOKE_USER_ID}" \
  -H 'X-Role: USER' \
  -H "Idempotency-Key: ${idempotency_key}" \
  --data "{
    \"order_id\":\"order-${idempotency_key}\",
    \"user_id\":\"${SMOKE_USER_ID}\",
    \"merchant_id\":\"${SMOKE_MERCHANT_ID}\",
    \"description\":\"Package tracking has not updated; smoke validation.\",
    \"channel\":\"WEB\"
  }")"
if [[ "${case_status}" != "201" ]]; then
  echo "case creation failed with HTTP ${case_status}" >&2
  cat "${RESPONSE_FILE}" >&2
  exit 1
fi

case_id="$(python - "${RESPONSE_FILE}" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)
if payload.get("success") is not True:
    raise SystemExit("case creation envelope was unsuccessful")
case_id = payload.get("data", {}).get("id", "")
if not case_id.startswith("CASE_"):
    raise SystemExit("case creation returned an invalid case id")
print(case_id)
PY
)"

query_status="$(curl --silent --show-error --max-time 30 \
  --output "${RESPONSE_FILE}" --write-out '%{http_code}' \
  "${NGINX_BASE_URL}/api/v1/cases/${case_id}" \
  -H "X-User-Id: ${SMOKE_USER_ID}" \
  -H 'X-Role: USER')"
if [[ "${query_status}" != "200" ]]; then
  echo "case query failed with HTTP ${query_status}" >&2
  cat "${RESPONSE_FILE}" >&2
  exit 1
fi

python - "${RESPONSE_FILE}" "${case_id}" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)
if payload.get("success") is not True:
    raise SystemExit("case query envelope was unsuccessful")
if payload.get("data", {}).get("id") != sys.argv[2]:
    raise SystemExit("case query returned a different case")
PY

echo "case-create-query-through-nginx: PASS (${case_id})"
echo "Smoke tests passed."
