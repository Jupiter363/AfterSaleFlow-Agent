#!/usr/bin/env bash
set -euo pipefail

NGINX_BASE_URL="${NGINX_BASE_URL:-http://localhost:8080}"
JAVA_BASE_URL="${JAVA_BASE_URL:-http://localhost:18080}"
AGENT_BASE_URL="${AGENT_BASE_URL:-http://localhost:18000}"
OCR_BASE_URL="${OCR_BASE_URL:-http://localhost:18010}"

assert_http_200() {
  local name="$1"
  local url="$2"
  local status
  status="$(curl --silent --show-error --output /tmp/smoke-response \
    --write-out '%{http_code}' "${url}")"
  if [[ "${status}" != "200" ]]; then
    echo "${name} failed with HTTP ${status}" >&2
    cat /tmp/smoke-response >&2
    exit 1
  fi
  echo "${name}: PASS"
}

assert_http_200 "nginx" "${NGINX_BASE_URL}/healthz"
assert_http_200 "java-api-service" "${JAVA_BASE_URL}/actuator/health"
assert_http_200 "python-agent-service" "${AGENT_BASE_URL}/health"
assert_http_200 "ocr-parser-service" "${OCR_BASE_URL}/health"

unhealthy="$(docker compose ps --format json | \
  python -c 'import json,sys; rows=[json.loads(x) for x in sys.stdin if x.strip()]; print("\\n".join(r["Service"] for r in rows if r.get("Health") not in ("", "healthy")))'"
if [[ -n "${unhealthy}" ]]; then
  echo "Unhealthy services:" >&2
  echo "${unhealthy}" >&2
  exit 1
fi

echo "Smoke tests passed."

