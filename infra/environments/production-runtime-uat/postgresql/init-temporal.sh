#!/usr/bin/env bash
set -euo pipefail

[[ "$TEMPORAL_DB_USER" =~ ^[a-z][a-z0-9_]{0,62}$ ]] || exit 64
test -n "$TEMPORAL_DB_PASSWORD"

psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set ON_ERROR_STOP=1 --set role_name="$TEMPORAL_DB_USER" \
  --set role_password="$TEMPORAL_DB_PASSWORD" <<'SQL'
select format('create role %I login nosuperuser nocreatedb nocreaterole noinherit noreplication password %L', :'role_name', :'role_password')
where not exists (select 1 from pg_roles where rolname = :'role_name')
\gexec
SQL

for database in temporal temporal_visibility; do
  psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    --set ON_ERROR_STOP=1 --set database_name="$database" \
    --set role_name="$TEMPORAL_DB_USER" <<'SQL'
select format('create database %I owner %I', :'database_name', :'role_name')
where not exists (select 1 from pg_database where datname = :'database_name')
\gexec
select format('revoke all on database %I from public', :'database_name')
\gexec
SQL
done
