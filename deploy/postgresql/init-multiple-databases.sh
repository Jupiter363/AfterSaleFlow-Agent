#!/usr/bin/env bash
# 文件作用：部署配置文件，描述数据库、中间件、代理、对象存储或运行时初始化逻辑。
# 说明：本注释用于帮助读者先了解脚本用途，再执行或修改脚本。

set -euo pipefail

# 业务位置：【系统支撑代码】create_database：把 当前模块输入 组装为本块需要的 当前阶段业务数据，供 相邻模块调用 使用。上游：当前模块输入。下游：相邻模块调用。边界：不得绕过系统权限和审计。
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

