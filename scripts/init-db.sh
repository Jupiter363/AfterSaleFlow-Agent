#!/usr/bin/env bash
# 文件作用：项目运维脚本，用于本地开发、环境初始化、密钥生成或接口校验。
# 说明：本注释用于帮助读者先了解脚本用途，再执行或修改脚本。

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

docker compose up -d postgresql
docker compose exec -T postgresql sh -c \
  'until pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"; do sleep 2; done'
MSYS_NO_PATHCONV=1 docker compose run --rm --entrypoint java \
  java-api-service \
  -Dloader.main=com.example.dispute.database.FlywayMigrationMain \
  -cp /home/app/app.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher
