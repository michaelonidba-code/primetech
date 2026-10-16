#!/bin/sh
# Registers the Kafka ingestion supervisor once the Druid router answers. Re-submitting the same
# spec is safe: Druid updates the existing supervisor for the datasource.
set -eu
ROUTER="${ROUTER:-http://router:8888}"

echo "waiting for Druid at $ROUTER"
until wget -q -O /dev/null "$ROUTER/status/health"; do sleep 3; done
# The overlord can come up after the router; retry until the spec is accepted.
until wget -q -O - --header='Content-Type: application/json' \
    --post-file=/specs/ad-events-supervisor.json "$ROUTER/druid/indexer/v1/supervisor"; do
  sleep 5
done
echo
echo "supervisor for ad_events submitted"
