#!/usr/bin/env sh
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

