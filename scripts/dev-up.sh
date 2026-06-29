#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

./scripts/generate-secrets.sh

if grep -q '^DEEPSEEK_API_KEY=__PASTE_YOUR_DEEPSEEK_API_KEY_HERE__$' .env \
    && [[ "${DEEPSEEK_API_KEY:-}" != sk-* ]]; then
  echo "DEEPSEEK_API_KEY is not configured in .env." >&2
  exit 1
fi

docker compose config --quiet
docker compose up -d --build --wait --wait-timeout "${STARTUP_TIMEOUT_SECONDS:-900}"
docker compose ps

if [[ "${RUN_SMOKE_TEST:-true}" == "true" ]]; then
  ./scripts/smoke-test.sh
fi
