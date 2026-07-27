#!/usr/bin/env sh
set -eu

mc alias set target "$MINIO_ENDPOINT" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
for bucket in \
  target-e2e-evidence-original \
  target-e2e-evidence-desensitized \
  target-e2e-intake-activation \
  target-e2e-ocr-temp \
  target-e2e-policy-files \
  target-e2e-forensic-exports; do
  mc mb --ignore-existing "target/$bucket"
  mc anonymous set none "target/$bucket"
done
