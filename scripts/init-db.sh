#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

docker compose up -d postgresql
docker compose exec -T postgresql sh -c \
  'until pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"; do sleep 2; done'
docker compose run --rm java-api-service \
  java -Dspring.flyway.enabled=true -jar /app/app.jar --spring.main.web-application-type=none

