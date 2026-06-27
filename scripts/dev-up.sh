#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

./scripts/generate-secrets.sh

if grep -q '^DEEPSEEK_API_KEY=__PASTE_YOUR_DEEPSEEK_API_KEY_HERE__$' .env; then
  echo "DEEPSEEK_API_KEY is not configured in .env." >&2
  exit 1
fi

docker compose config --quiet
docker compose up -d --build
docker compose ps

