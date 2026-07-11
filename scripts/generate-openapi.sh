#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT="${ROOT_DIR}/docs/api/openapi.json"
mkdir -p "$(dirname "${OUTPUT}")"

curl --fail --silent --show-error \
  "${JAVA_BASE_URL:-http://localhost:8080}/v3/api-docs" \
  --output "${OUTPUT}"
echo "OpenAPI written to ${OUTPUT}."
