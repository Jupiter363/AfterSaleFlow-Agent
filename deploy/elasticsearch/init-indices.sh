#!/usr/bin/env sh
set -eu

for index in policy_index evidence_index case_index; do
  status="$(curl -sS -o /tmp/index-result -w '%{http_code}' \
    -X PUT "${ELASTICSEARCH_URL}/${index}" \
    -H 'Content-Type: application/json' \
    -d '{"settings":{"number_of_shards":1,"number_of_replicas":0}}')"
  if [ "$status" != "200" ] && [ "$status" != "400" ]; then
    cat /tmp/index-result
    exit 1
  fi
done

touch /tmp/initialized
tail -f /dev/null

