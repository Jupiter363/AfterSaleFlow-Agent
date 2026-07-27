#!/usr/bin/env bash
set -euo pipefail

for value in "$GRAPH_DB_NAME" "$GRAPH_DB_SCHEMA" "$GRAPH_OWNER_USER" "$GRAPH_MIGRATOR_USER" "$GRAPH_RUNTIME_USER" "$GRAPH_RETENTION_USER"; do
  [[ "$value" =~ ^[a-z][a-z0-9_]{0,62}$ ]] || exit 64
done

create_login() {
  psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 \
    --set role_name="$1" --set role_password="$2" <<'SQL'
select format('create role %I login nosuperuser nocreatedb nocreaterole noinherit noreplication password %L', :'role_name', :'role_password')
where not exists (select 1 from pg_roles where rolname = :'role_name')
\gexec
SQL
}

psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 \
  --set role_name="$GRAPH_OWNER_USER" <<'SQL'
select format('create role %I nologin nosuperuser nocreatedb nocreaterole inherit noreplication', :'role_name')
where not exists (select 1 from pg_roles where rolname = :'role_name')
\gexec
SQL
create_login "$GRAPH_MIGRATOR_USER" "$GRAPH_MIGRATOR_PASSWORD"
create_login "$GRAPH_RUNTIME_USER" "$GRAPH_RUNTIME_PASSWORD"
create_login "$GRAPH_RETENTION_USER" "$GRAPH_RETENTION_PASSWORD"

psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 \
  --set owner_name="$GRAPH_OWNER_USER" --set migrator_name="$GRAPH_MIGRATOR_USER" \
  --set database_name="$GRAPH_DB_NAME" <<'SQL'
select format('grant %I to %I', :'owner_name', :'migrator_name')
where not pg_has_role(:'migrator_name', :'owner_name', 'MEMBER')
\gexec
select format('create database %I owner %I', :'database_name', current_user)
where not exists (select 1 from pg_database where datname = :'database_name')
\gexec
select format('revoke all on database %I from public', :'database_name')
\gexec
SQL
psql --username "$POSTGRES_USER" --dbname "$GRAPH_DB_NAME" --set ON_ERROR_STOP=1 \
  --set database_name="$GRAPH_DB_NAME" --set schema_name="$GRAPH_DB_SCHEMA" \
  --set owner_name="$GRAPH_OWNER_USER" --set migrator_name="$GRAPH_MIGRATOR_USER" \
  --set runtime_name="$GRAPH_RUNTIME_USER" --set retention_name="$GRAPH_RETENTION_USER" <<'SQL'
select format('create schema if not exists %I authorization %I', :'schema_name', :'owner_name')
\gexec
revoke all on schema public from public;
select format('revoke all on schema %I from public', :'schema_name')
\gexec
select format('revoke temporary on database %I from public, %I, %I, %I, %I', :'database_name', :'owner_name', :'migrator_name', :'runtime_name', :'retention_name')
\gexec
select format('grant connect on database %I to %I, %I, %I', :'database_name', :'migrator_name', :'runtime_name', :'retention_name')
\gexec
select format('grant usage on schema %I to %I, %I', :'schema_name', :'runtime_name', :'retention_name')
\gexec
select format('alter role %I in database %I set search_path to %I, pg_catalog, pg_temp', :'runtime_name', :'database_name', :'schema_name')
\gexec
select format('alter role %I in database %I set search_path to %I, pg_catalog, pg_temp', :'retention_name', :'database_name', :'schema_name')
\gexec
SQL
