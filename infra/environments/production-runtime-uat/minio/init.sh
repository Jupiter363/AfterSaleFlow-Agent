#!/usr/bin/env sh
set -eu

mc alias set target "$MINIO_ENDPOINT" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
for bucket in \
  production-runtime-evidence-original \
  production-runtime-evidence-desensitized \
  production-runtime-intake-activation \
  production-runtime-ocr-temp \
  production-runtime-policy-files \
  production-runtime-forensic-exports; do
  mc mb --ignore-existing "target/$bucket"
  mc anonymous set none "target/$bucket"
done
