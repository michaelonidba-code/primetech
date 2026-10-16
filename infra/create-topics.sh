#!/usr/bin/env bash
# Creates the event topics. Safe to re-run: existing topics are left alone.
set -euo pipefail

BROKERS="${BROKERS:-redpanda:9092}"

create() {
  local topic="$1" partitions="$2" retention_ms="$3"
  if rpk topic describe "$topic" -X brokers="$BROKERS" >/dev/null 2>&1; then
    echo "topic $topic already exists"
  else
    rpk topic create "$topic" -X brokers="$BROKERS" \
      --partitions "$partitions" \
      --topic-config retention.ms="$retention_ms" \
      --topic-config compression.type=producer
  fi
}

# 12 partitions lets the stream processor scale to 12 parallel readers.
create ad-events.v1 12 604800000      # 7 days, long enough to replay a week of reporting
create ad-events-dlq.v1 3 1209600000  # 14 days, so bad payloads can be investigated
