#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

docker compose up -d elasticsearch elasticsearch-init
docker compose exec -T elasticsearch-init test -f /tmp/initialized

