#!/usr/bin/env bash
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

generate_secret() {
  openssl rand -hex 24
}

generate_user() {
  printf 'user_%s' "$(openssl rand -hex 4)"
}

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
