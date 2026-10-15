<script setup lang="ts">
interface Pipeline {
  available: boolean
  error?: string
  job: { name: string; state: string; uptimeMs: number } | null
  checkpoints?: {
    completed: number
    failed: number
    restored: number
    lastCompletedAt: number | null
    lastDurationMs: number | null
    lastStateBytes: number | null
  }
  metrics?: {
    cleanPerSecond: number | null
    duplicatesDropped: number | null
    ivtFlagged: number | null
    metadataMissing: number | null
    lateDropped: number | null
  }
}

const { data, reachable } = usePolled<Pipeline>('/api/pipeline', 5000)
const now = ref(Date.now())
let clock: ReturnType<typeof setInterval> | undefined
onMounted(() => (clock = setInterval(() => (now.value = Date.now()), 1000)))
onBeforeUnmount(() => clearInterval(clock))

const running = computed(() => data.value?.job?.state === 'RUNNING')
const checkpointAge = computed(() => {
  const at = data.value?.checkpoints?.lastCompletedAt
  return at ? now.value - at : null
})
// Checkpoints run every 10s; one older than a minute means output is no longer being committed.
const checkpointStale = computed(() => checkpointAge.value !== null && checkpointAge.value > 60_000)
</script>

<template>
  <section class="card">
    <h2>Stream processing · Flink</h2>
    <p v-if="!reachable" class="muted">
      Needs the server routes from the Docker stack (<code>docker compose up</code>); not available on the static site.
    </p>
    <p v-else-if="data && !data.available" class="muted">Flink is not reachable yet. <code>{{ data.error }}</code></p>
    <p v-else-if="data && !data.job" class="muted">No job submitted yet.</p>
    <div v-else-if="data?.job" class="tiles">
      <StatTile
        label="Job"
        :value="data.job.state"
        :detail="`up ${formatDuration(data.job.uptimeMs)}`"
        :tone="running ? 'good' : 'critical'"
      />
      <StatTile
        label="Clean events/s"
        :value="formatWhole(data.metrics?.cleanPerSecond ?? 0)"
        detail="deduplicated, enriched, IVT-checked"
      />
      <StatTile
        label="Duplicates dropped"
        :value="formatCompact(data.metrics?.duplicatesDropped ?? 0)"
        detail="by event id, 30 min window"
      />
      <StatTile
        label="Invalid traffic flagged"
        :value="formatCompact(data.metrics?.ivtFlagged ?? 0)"
        detail="kept, not billed"
      />
      <StatTile
        label="Last checkpoint"
        :value="checkpointAge === null ? '—' : `${formatDuration(checkpointAge)} ago`"
        :detail="`${data.checkpoints?.completed ?? 0} ok · ${data.checkpoints?.failed ?? 0} failed · ${data.checkpoints?.restored ?? 0} restores`"
        :tone="checkpointStale ? 'critical' : 'good'"
      />
    </div>
  </section>
</template>

<style scoped>
.tiles {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
  gap: 12px;
}
.tiles :deep(.card) {
  background: var(--page);
}
p {
  margin: 0;
}
</style>
