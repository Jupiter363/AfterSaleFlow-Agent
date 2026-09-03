#!/usr/bin/env bash
set -euo pipefail

require_identifier() {
  local name="$1"
  local value="$2"
  if [[ ! "${value}" =~ ^[a-z][a-z0-9_]{0,62}$ ]]; then
    printf 'Invalid PostgreSQL identifier for %s\n' "${name}" >&2
    exit 1
  fi
}

require_secret() {
  local name="$1"
  local value="$2"
  if [[ -z "${value}" || "${value}" == "__GENERATED_BY_CODEX__" ]]; then
    printf 'Missing PostgreSQL secret for %s\n' "${name}" >&2
    exit 1
  fi
}

create_login_role() {
  local role="$1"
  local password="$2"
  psql --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" \
    --set ON_ERROR_STOP=1 --set role_name="${role}" --set role_password="${password}" <<'SQL'
select format(
    'create role %I login nosuperuser nocreatedb nocreaterole noinherit noreplication password %L',
    :'role_name', :'role_password'
)
where not exists (select 1 from pg_roles where rolname = :'role_name')
\gexec
SQL
}

create_owner_role() {
  local role="$1"
  psql --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" \
    --set ON_ERROR_STOP=1 --set role_name="${role}" <<'SQL'
select format(
    'create role %I nologin nosuperuser nocreatedb nocreaterole inherit noreplication',
    :'role_name'
)
where not exists (select 1 from pg_roles where rolname = :'role_name')
\gexec
SQL
}

grant_role() {
  local granted_role="$1"
  local member_role="$2"
  psql --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" \
    --set ON_ERROR_STOP=1 --set granted_role="${granted_role}" \
    --set member_role="${member_role}" <<'SQL'
select format('grant %I to %I', :'granted_role', :'member_role')
where not pg_has_role(:'member_role', :'granted_role', 'MEMBER')
\gexec
SQL
}

create_isolated_database() {
  local database="$1"
  local owner="$2"
  psql --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" \
    --set ON_ERROR_STOP=1 --set database_name="${database}" --set owner_name="${owner}" <<'SQL'
select format('create database %I owner %I', :'database_name', :'owner_name')
where not exists (select 1 from pg_database where datname = :'database_name')
\gexec
select format('alter database %I owner to %I', :'database_name', :'owner_name')
\gexec
select format('revoke all on database %I from public', :'database_name')
\gexec
select format('grant connect, temporary on database %I to %I', :'database_name', :'owner_name')
\gexec
SQL
}

JAVA_DB_NAME="${JAVA_DB_NAME:-dispute_system}"
JAVA_DB_USER="${JAVA_DB_USER:-dispute_app}"
JAVA_DB_PASSWORD="${JAVA_DB_PASSWORD:?JAVA_DB_PASSWORD is required}"
TEMPORAL_DB_NAME="${TEMPORAL_DB_NAME:-temporal}"
TEMPORAL_DB_USER="${TEMPORAL_DB_USER:-temporal_app}"
TEMPORAL_DB_PASSWORD="${TEMPORAL_DB_PASSWORD:?TEMPORAL_DB_PASSWORD is required}"
LANGFUSE_DB_NAME="${LANGFUSE_DB_NAME:-langfuse}"
LANGFUSE_DB_USER="${LANGFUSE_DB_USER:-langfuse_app}"
LANGFUSE_DB_PASSWORD="${LANGFUSE_DB_PASSWORD:?LANGFUSE_DB_PASSWORD is required}"
LITELLM_DB_NAME="${LITELLM_DB_NAME:-litellm}"
LITELLM_DB_USER="${LITELLM_DB_USER:-litellm_app}"
LITELLM_DB_PASSWORD="${LITELLM_DB_PASSWORD:?LITELLM_DB_PASSWORD is required}"
GRAPH_DB_NAME="${GRAPH_DB_NAME:-dispute_graph}"
GRAPH_DB_SCHEMA="${GRAPH_DB_SCHEMA:-graph_runtime}"
GRAPH_OWNER_USER="${GRAPH_OWNER_USER:-graph_owner}"
GRAPH_MIGRATOR_USER="${GRAPH_MIGRATOR_USER:-graph_migrator}"
GRAPH_MIGRATOR_PASSWORD="${GRAPH_MIGRATOR_PASSWORD:?GRAPH_MIGRATOR_PASSWORD is required}"
GRAPH_RUNTIME_USER="${GRAPH_RUNTIME_USER:-graph_runtime}"
GRAPH_RUNTIME_PASSWORD="${GRAPH_RUNTIME_PASSWORD:?GRAPH_RUNTIME_PASSWORD is required}"
GRAPH_RETENTION_USER="${GRAPH_RETENTION_USER:-graph_retention}"
GRAPH_RETENTION_PASSWORD="${GRAPH_RETENTION_PASSWORD:?GRAPH_RETENTION_PASSWORD is required}"

for pair in \
  "JAVA_DB_NAME:${JAVA_DB_NAME}" \
  "JAVA_DB_USER:${JAVA_DB_USER}" \
  "TEMPORAL_DB_NAME:${TEMPORAL_DB_NAME}" \
  "TEMPORAL_DB_USER:${TEMPORAL_DB_USER}" \
  "LANGFUSE_DB_NAME:${LANGFUSE_DB_NAME}" \
  "LANGFUSE_DB_USER:${LANGFUSE_DB_USER}" \
  "LITELLM_DB_NAME:${LITELLM_DB_NAME}" \
  "LITELLM_DB_USER:${LITELLM_DB_USER}" \
  "GRAPH_DB_NAME:${GRAPH_DB_NAME}" \
  "GRAPH_DB_SCHEMA:${GRAPH_DB_SCHEMA}" \
  "GRAPH_OWNER_USER:${GRAPH_OWNER_USER}" \
  "GRAPH_MIGRATOR_USER:${GRAPH_MIGRATOR_USER}" \
  "GRAPH_RUNTIME_USER:${GRAPH_RUNTIME_USER}" \
  "GRAPH_RETENTION_USER:${GRAPH_RETENTION_USER}"; do
  require_identifier "${pair%%:*}" "${pair#*:}"
done

for pair in \
  "JAVA_DB_PASSWORD:${JAVA_DB_PASSWORD}" \
  "TEMPORAL_DB_PASSWORD:${TEMPORAL_DB_PASSWORD}" \
  "LANGFUSE_DB_PASSWORD:${LANGFUSE_DB_PASSWORD}" \
  "LITELLM_DB_PASSWORD:${LITELLM_DB_PASSWORD}" \
  "GRAPH_MIGRATOR_PASSWORD:${GRAPH_MIGRATOR_PASSWORD}" \
  "GRAPH_RUNTIME_PASSWORD:${GRAPH_RUNTIME_PASSWORD}" \
  "GRAPH_RETENTION_PASSWORD:${GRAPH_RETENTION_PASSWORD}"; do
  require_secret "${pair%%:*}" "${pair#*:}"
done

create_login_role "${JAVA_DB_USER}" "${JAVA_DB_PASSWORD}"
create_login_role "${TEMPORAL_DB_USER}" "${TEMPORAL_DB_PASSWORD}"
create_login_role "${LANGFUSE_DB_USER}" "${LANGFUSE_DB_PASSWORD}"
create_login_role "${LITELLM_DB_USER}" "${LITELLM_DB_PASSWORD}"
create_owner_role "${GRAPH_OWNER_USER}"
create_login_role "${GRAPH_MIGRATOR_USER}" "${GRAPH_MIGRATOR_PASSWORD}"
create_login_role "${GRAPH_RUNTIME_USER}" "${GRAPH_RUNTIME_PASSWORD}"
create_login_role "${GRAPH_RETENTION_USER}" "${GRAPH_RETENTION_PASSWORD}"
grant_role "${GRAPH_OWNER_USER}" "${GRAPH_MIGRATOR_USER}"

create_isolated_database "${JAVA_DB_NAME}" "${JAVA_DB_USER}"
create_isolated_database "${TEMPORAL_DB_NAME}" "${TEMPORAL_DB_USER}"
create_isolated_database "temporal_visibility" "${TEMPORAL_DB_USER}"
create_isolated_database "${LANGFUSE_DB_NAME}" "${LANGFUSE_DB_USER}"
create_isolated_database "${LITELLM_DB_NAME}" "${LITELLM_DB_USER}"
create_isolated_database "${GRAPH_DB_NAME}" "${POSTGRES_USER}"

psql --username "${POSTGRES_USER}" --dbname "${GRAPH_DB_NAME}" \
  --set ON_ERROR_STOP=1 \
  --set graph_database="${GRAPH_DB_NAME}" \
  --set graph_schema="${GRAPH_DB_SCHEMA}" \
  --set graph_owner="${GRAPH_OWNER_USER}" \
  --set graph_migrator="${GRAPH_MIGRATOR_USER}" \
  --set graph_runtime="${GRAPH_RUNTIME_USER}" \
  --set graph_retention="${GRAPH_RETENTION_USER}" <<'SQL'
select format('create schema if not exists %I authorization %I', :'graph_schema', :'graph_owner')
\gexec
revoke all on schema public from public;
select format('revoke all on schema %I from public', :'graph_schema')
\gexec
select format(
    'revoke temporary on database %I from public, %I, %I, %I, %I',
    :'graph_database', :'graph_owner', :'graph_migrator', :'graph_runtime', :'graph_retention'
)
\gexec
select format('grant connect on database %I to %I, %I, %I',
              :'graph_database', :'graph_migrator', :'graph_runtime', :'graph_retention')
\gexec
select format('grant usage on schema %I to %I, %I',
              :'graph_schema', :'graph_runtime', :'graph_retention')
\gexec
select format('alter role %I in database %I set search_path to %I, pg_catalog, pg_temp',
              :'graph_runtime', :'graph_database', :'graph_schema')
\gexec
select format('alter role %I in database %I set search_path to %I, pg_catalog, pg_temp',
              :'graph_retention', :'graph_database', :'graph_schema')
\gexec
SQL
