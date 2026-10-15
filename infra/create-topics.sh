#!/usr/bin/env bash
# Creates the event topics. Safe to re-run: existing topics are left alone.
set -euo pipefail

BROKERS="${BROKERS:-redpanda:9092}"

# create <topic> <partitions> [topic-config ...]
create() {
  local topic="$1" partitions="$2"
  shift 2
  if rpk topic describe "$topic" -X brokers="$BROKERS" >/dev/null 2>&1; then
    echo "topic $topic already exists"
    return
  fi
  local args=()
  for c in "$@"; do args+=(--topic-config "$c"); done
  rpk topic create "$topic" -X brokers="$BROKERS" --partitions "$partitions" "${args[@]}"
}

WEEK=604800000
TWO_WEEKS=1209600000

# 12 partitions lets the stream processor scale to 12 parallel readers.
create ad-events.v1 12 retention.ms=$WEEK compression.type=producer
# Bad payloads, kept longer so they can be investigated.
create ad-events-dlq.v1 3 retention.ms=$TWO_WEEKS compression.type=producer
# Reference data: compaction keeps the latest record per campaign forever.
create campaign-metadata.v1 1 cleanup.policy=compact
# Output of the stream processor: what reporting and billing read.
create ad-events-clean.v1 12 retention.ms=$WEEK compression.type=producer
# Upsert stream of minute totals: compaction keeps the latest revision of each line item minute,
# deletion drops minutes older than a week.
create line-item-minutes.v1 6 cleanup.policy=compact,delete retention.ms=$WEEK
# Ad server audit log: one record per ad request, joined to events by auction id.
create ad-decisions.v1 12 retention.ms=$WEEK compression.type=producer
# Direct line item terms (tier, budget per period), read by the pacing service.
create line-items.v1 1 cleanup.policy=compact
