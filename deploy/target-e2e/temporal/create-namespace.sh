#!/usr/bin/env sh
set -eu

namespace="$TARGET_E2E_TEMPORAL_NAMESPACE"
case "$namespace" in
  after-sale-flow-p9-*) ;;
  *) echo "target E2E namespace is not isolated" >&2; exit 64 ;;
esac

if tctl --address temporal-server:7233 --namespace "$namespace" namespace describe >/dev/null 2>&1; then
  exit 0
fi
tctl --address temporal-server:7233 namespace register \
  --namespace "$namespace" --retention 1
