#!/usr/bin/env bash
# 文件作用：项目运维脚本，用于本地开发、环境初始化、密钥生成或接口校验。
# 说明：本注释用于帮助读者先了解脚本用途，再执行或修改脚本。

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT="${ROOT_DIR}/docs/api/openapi.json"
mkdir -p "$(dirname "${OUTPUT}")"

curl --fail --silent --show-error \
  "${JAVA_BASE_URL:-http://localhost:8080}/v3/api-docs" \
  --output "${OUTPUT}"
echo "OpenAPI written to ${OUTPUT}."
