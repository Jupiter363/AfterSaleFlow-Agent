#!/usr/bin/env sh
set -eu

require_nonempty_build_component() {
  name="$1"
  value="$2"
  if [ -z "$value" ]; then
    echo "$name must be set" >&2
    exit 64
  fi
  case "$value" in
    *[!A-Za-z0-9._-]* | -* | .* | _*)
      echo "$name is not a valid Build ID component" >&2
      exit 64
      ;;
  esac
}

namespace="${PRODUCTION_RUNTIME_TEMPORAL_NAMESPACE:-}"
control_build_component="${PRODUCTION_RUNTIME_CONTROL_BUILD_ID:-}"
agent_build_component="${PRODUCTION_RUNTIME_AGENT_BUILD_ID:-}"

if [ -z "$namespace" ]; then
  echo "PRODUCTION_RUNTIME_TEMPORAL_NAMESPACE must be set" >&2
  exit 64
fi
case "$namespace" in
  after-sale-flow-p9-*) ;;
  *) echo "production runtime namespace is not isolated" >&2; exit 64 ;;
esac

require_nonempty_build_component PRODUCTION_RUNTIME_CONTROL_BUILD_ID "$control_build_component"
require_nonempty_build_component PRODUCTION_RUNTIME_AGENT_BUILD_ID "$agent_build_component"

if ! command -v tctl >/dev/null 2>&1; then
  echo "tctl is required to create the production runtime namespace" >&2
  exit 69
fi
if ! command -v temporal >/dev/null 2>&1; then
  echo "Temporal CLI 1.25+ is required to configure Build ID routing" >&2
  exit 69
fi

temporal_address="temporal-server:7233"
control_build_id="after-sale-control.${control_build_component}"
agent_build_id="after-sale-agent.${agent_build_component}"

attempt=1
namespace_ready=false
while [ "$attempt" -le 30 ]; do
  if tctl --address "$temporal_address" --namespace "$namespace" namespace describe >/dev/null 2>&1; then
    namespace_ready=true
    break
  fi
  tctl --address "$temporal_address" --namespace "$namespace" namespace register \
    --retention 1 >/dev/null 2>&1 || true
  attempt=$((attempt + 1))
  sleep 2
done

if [ "$namespace_ready" != true ]; then
  echo "production runtime Temporal namespace was not ready after 30 attempts" >&2
  exit 1
fi

legacy_build_id_json() {
  temporal --output json task-queue get-build-ids \
    --address "$temporal_address" \
    --namespace "$namespace" \
    --task-queue "$1" \
    --max-sets 0
}

ensure_default_legacy_build_id() {
  task_queue="$1"
  build_id="$2"
  expected_compact="[{\"buildIds\":[\"${build_id}\"],\"defaultForSet\":\"${build_id}\",\"isDefaultSet\":true}]"

  existing="$(legacy_build_id_json "$task_queue")" || {
    echo "could not query Build ID routing for task queue $task_queue" >&2
    exit 1
  }
  compact="$(printf '%s' "$existing" | tr -d '\r\n\t ')"

  if [ "$compact" = null ]; then
    temporal task-queue update-build-ids add-new-default \
      --address "$temporal_address" \
      --namespace "$namespace" \
      --task-queue "$task_queue" \
      --build-id "$build_id" >/dev/null || {
        echo "could not register default Build ID for task queue $task_queue" >&2
        exit 1
      }
    existing="$(legacy_build_id_json "$task_queue")" || {
      echo "could not verify Build ID routing for task queue $task_queue" >&2
      exit 1
    }
    compact="$(printf '%s' "$existing" | tr -d '\r\n\t ')"
  fi

  # A reused namespace is safe only when the sole legacy default is this candidate.
  # Do not promote or mutate a conflicting Build ID set during production runtime startup.
  if [ "$compact" != "$expected_compact" ]; then
    echo "conflicting or incomplete Build ID routing for task queue $task_queue" >&2
    exit 1
  fi
}

for task_queue in \
  case-control \
  room-control \
  notification-and-tools \
  production-runtime-case-dispute; do
  ensure_default_legacy_build_id "$task_queue" "$control_build_id"
done
ensure_default_legacy_build_id agent-execution "$agent_build_id"
