#!/usr/bin/env bash
set -euo pipefail

for value in "$DOMAIN_DB_NAME" "$DOMAIN_DB_USER"; do
  [[ "$value" =~ ^[a-z][a-z0-9_]{0,62}$ ]] || exit 64
done
test -n "$DOMAIN_DB_PASSWORD"

psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set ON_ERROR_STOP=1 --set role_name="$DOMAIN_DB_USER" \
  --set role_password="$DOMAIN_DB_PASSWORD" --set database_name="$DOMAIN_DB_NAME" <<'SQL'
select format('create role %I login nosuperuser nocreatedb nocreaterole noinherit noreplication password %L', :'role_name', :'role_password')
where not exists (select 1 from pg_roles where rolname = :'role_name')
\gexec
select format('create database %I owner %I', :'database_name', :'role_name')
where not exists (select 1 from pg_database where datname = :'database_name')
\gexec
select format('revoke all on database %I from public', :'database_name')
\gexec
select format('grant connect, temporary on database %I to %I', :'database_name', :'role_name')
\gexec
SQL
