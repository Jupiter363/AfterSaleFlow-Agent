#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

if [[ "${CONFIRM_RESET:-}" != "YES" ]]; then
  echo "Refusing destructive reset. Re-run with CONFIRM_RESET=YES." >&2
  exit 1
fi

docker compose down --volumes --remove-orphans
docker compose up -d --build

