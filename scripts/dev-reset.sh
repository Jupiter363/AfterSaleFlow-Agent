#!/usr/bin/env bash
# 文件作用：项目运维脚本，用于本地开发、环境初始化、密钥生成或接口校验。
# 说明：本注释用于帮助读者先了解脚本用途，再执行或修改脚本。

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

if [[ "${CONFIRM_RESET:-}" != "YES" ]]; then
  echo "Refusing destructive reset. Re-run with CONFIRM_RESET=YES." >&2
  exit 1
fi

docker compose down --volumes --remove-orphans
./scripts/dev-up.sh
