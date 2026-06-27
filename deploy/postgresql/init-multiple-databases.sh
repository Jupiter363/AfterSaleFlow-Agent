#!/usr/bin/env bash
set -euo pipefail

create_database() {
  local database="$1"
  if ! psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -tAc \
      "SELECT 1 FROM pg_database WHERE datname = '${database}'" | grep -q 1; then
    psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
      --command "CREATE DATABASE \"${database}\""
  fi
}

create_database "${JAVA_DB_NAME:-dispute_system}"
create_database "${TEMPORAL_DB_NAME:-temporal}"
create_database "temporal_visibility"
create_database "${LANGFUSE_DB_NAME:-langfuse}"
create_database "${LITELLM_DB_NAME:-litellm}"

