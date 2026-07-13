#!/usr/bin/env bash
# 文件作用：项目运维脚本，用于本地开发、环境初始化、密钥生成或接口校验。
# 说明：本注释用于帮助读者先了解脚本用途，再执行或修改脚本。

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

./scripts/generate-secrets.sh

dashscope_api_key="${DASHSCOPE_API_KEY:-}"
if [[ -z "${dashscope_api_key}" ]]; then
  dashscope_api_key="$(sed -n 's/^DASHSCOPE_API_KEY=//p' .env | tail -n 1)"
fi
if [[ -z "${dashscope_api_key}" \
    || "${dashscope_api_key}" == "__PASTE_YOUR_DASHSCOPE_API_KEY_HERE__" ]]; then
  echo "DASHSCOPE_API_KEY is not configured in .env." >&2
  exit 1
fi

docker compose config --quiet
docker compose up -d --build --wait --wait-timeout "${STARTUP_TIMEOUT_SECONDS:-900}"
docker compose ps

if [[ "${RUN_SMOKE_TEST:-true}" == "true" ]]; then
  ./scripts/smoke-test.sh
fi
