#!/usr/bin/env sh
set -eu

for index in target-e2e-policy target-e2e-evidence target-e2e-case; do
  code="$(curl -sS -o /tmp/result -w '%{http_code}' -X PUT "$ELASTICSEARCH_URL/$index" \
    -H 'Content-Type: application/json' \
    -d '{"settings":{"number_of_shards":1,"number_of_replicas":0}}')"
  test "$code" = 200 || test "$code" = 400 || { cat /tmp/result; exit 1; }
done
