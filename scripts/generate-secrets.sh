#!/usr/bin/env bash
# 文件作用：项目运维脚本，用于本地开发、环境初始化、密钥生成或接口校验。
# 说明：本注释用于帮助读者先了解脚本用途，再执行或修改脚本。

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROOT_DIR}/.env"
EXAMPLE_FILE="${ROOT_DIR}/.env.example"

if [[ ! -f "${ENV_FILE}" ]]; then
  cp "${EXAMPLE_FILE}" "${ENV_FILE}"
fi

while IFS= read -r line; do
  if [[ "${line}" =~ ^([A-Za-z_][A-Za-z0-9_]*)= ]]; then
    key="${BASH_REMATCH[1]}"
    if ! grep -q "^${key}=" "${ENV_FILE}"; then
      printf '%s\n' "${line}" >> "${ENV_FILE}"
    fi
  fi
done < "${EXAMPLE_FILE}"

# 业务位置：【开发与运维脚本】generate_secret：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 本地环境变量和容器服务 正确进入 可重复的初始化、启动或校验动作。上游：本地环境变量和容器服务。下游：可重复的初始化、启动或校验动作。边界：脚本不得写入真实生产数据。
generate_secret() {
  openssl rand -hex 24
}

# 业务位置：【开发与运维脚本】generate_user：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 本地环境变量和容器服务 正确进入 可重复的初始化、启动或校验动作。上游：本地环境变量和容器服务。下游：可重复的初始化、启动或校验动作。边界：脚本不得写入真实生产数据。
generate_user() {
  printf 'user_%s' "$(openssl rand -hex 4)"
}

# 业务位置：【开发与运维脚本】replace_if_placeholder：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 本地环境变量和容器服务 正确进入 可重复的初始化、启动或校验动作。上游：本地环境变量和容器服务。下游：可重复的初始化、启动或校验动作。边界：脚本不得写入真实生产数据。
replace_if_placeholder() {
  local key="$1"
  local value="$2"
  local temporary
  temporary="$(mktemp)"
  awk -v key="${key}" -v value="${value}" '
    $0 == key "=__GENERATED_BY_CODEX__" { print key "=" value; next }
    { print }
  ' "${ENV_FILE}" > "${temporary}"
  mv "${temporary}" "${ENV_FILE}"
}

replace_if_placeholder "POSTGRES_USER" "$(generate_user)"
replace_if_placeholder "POSTGRES_PASSWORD" "$(generate_secret)"
replace_if_placeholder "REDIS_PASSWORD" "$(generate_secret)"
replace_if_placeholder "MINIO_ROOT_USER" "$(generate_user)"
replace_if_placeholder "MINIO_ROOT_PASSWORD" "$(generate_secret)"
replace_if_placeholder "ELASTICSEARCH_PASSWORD" "$(generate_secret)"
replace_if_placeholder "LITELLM_MASTER_KEY" "sk-$(openssl rand -hex 24)"
replace_if_placeholder "LITELLM_SALT_KEY" "$(generate_secret)"
replace_if_placeholder "LANGFUSE_PUBLIC_KEY" "pk-lf-$(openssl rand -hex 16)"
replace_if_placeholder "LANGFUSE_SECRET_KEY" "sk-lf-$(openssl rand -hex 24)"
replace_if_placeholder "LANGFUSE_SALT" "$(generate_secret)"
replace_if_placeholder "LANGFUSE_NEXTAUTH_SECRET" "$(generate_secret)"
replace_if_placeholder "JAVA_SERVICE_SECRET" "$(generate_secret)"
replace_if_placeholder "PYTHON_AGENT_SERVICE_SECRET" "$(generate_secret)"
replace_if_placeholder "OCR_SERVICE_SECRET" "$(generate_secret)"

echo "Local .env generated or updated; secret values were not printed."
echo "Set DASHSCOPE_API_KEY in .env before starting services."
