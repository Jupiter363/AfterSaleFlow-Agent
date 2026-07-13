#!/usr/bin/env sh
# 文件作用：部署配置文件，描述数据库、中间件、代理、对象存储或运行时初始化逻辑。
# 说明：本注释用于帮助读者先了解脚本用途，再执行或修改脚本。

set -eu

mc alias set local "$MINIO_ENDPOINT" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"

for bucket in \
  "$MINIO_BUCKET_EVIDENCE_ORIGINAL" \
  "$MINIO_BUCKET_EVIDENCE_DESENSITIZED" \
  "$MINIO_BUCKET_OCR_TEMP" \
  "$MINIO_BUCKET_POLICY_FILES" \
  "$MINIO_BUCKET_REVIEW_EXPORTS"; do
  mc mb --ignore-existing "local/${bucket}"
  mc anonymous set none "local/${bucket}"
done

touch /tmp/initialized
tail -f /dev/null

