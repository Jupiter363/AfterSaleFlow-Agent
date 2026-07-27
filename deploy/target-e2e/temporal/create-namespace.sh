#!/usr/bin/env sh
set -eu

namespace="$TARGET_E2E_TEMPORAL_NAMESPACE"
case "$namespace" in
  after-sale-flow-p9-*) ;;
  *) echo "target E2E namespace is not isolated" >&2; exit 64 ;;
esac

attempt=1
while [ "$attempt" -le 30 ]; do
  if tctl --address temporal-server:7233 --namespace "$namespace" namespace describe >/dev/null 2>&1; then
    exit 0
  fi
  if tctl --address temporal-server:7233 --namespace "$namespace" namespace register \
    --retention 1; then
    exit 0
  fi
  attempt=$((attempt + 1))
  sleep 2
done

echo "target E2E Temporal namespace was not ready after 30 attempts" >&2
exit 1
