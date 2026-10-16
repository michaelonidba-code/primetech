<script setup lang="ts">
type Status = 'OK' | 'MISMATCH' | 'PENDING' | 'ERROR'
interface Result {
  check: string
  status: Status
  windowStartMs: number
  windowEndMs: number
  compared: number
  mismatches: number
  summary: string
  examples: string[]
  ranAtMs: number
}
interface CheckStats {
  latest: Result | null
  latestCompleted: Result | null
  recent: { status: Status; ranAtMs: number; mismatches: number }[]
}
interface AuditStats {
  available: boolean
  error?: string
  caughtUp?: boolean
  checks?: Record<string, CheckStats>
}

const { data, reachable } = usePolled<AuditStats>('/api/audit', 15_000)

const CHECKS: Record<string, { title: string; what: string }> = {
  'flink-vs-druid': {
    title: 'Stream totals vs analytics store',
    what: 'Flink’s exactly-once minute totals against Druid’s rollup, per line item and minute, once minutes can no longer change.',
  },
  'served-vs-decision-log': {
    title: 'Pacing counters vs decision log',
    what: 'Served spend in Redis, which pacing steers by, against every direct win in the ad server’s decision log.',
  },
  'budget-compliance': {
    title: 'Budget compliance',
    what: 'Delivered spend per line item, from Druid, against its budget for the last settled period.',
  },
  'delivered-vs-served': {
    title: 'Delivered vs served',
    what: 'Played, valid spend against served spend: too low means tracking is being lost, too high means invented spend.',
  },
}
const STATUS: Record<Status, { label: string; icon: string; tone: string }> = {
  OK: { label: 'Agrees', icon: '✓', tone: 'good' },
  MISMATCH: { label: 'Mismatch', icon: '!', tone: 'critical' },
  PENDING: { label: 'Waiting for data', icon: '○', tone: 'muted' },
  ERROR: { label: 'Source unavailable', icon: '▲', tone: 'warning' },
}

const rows = computed(() =>
  Object.entries(CHECKS).map(([key, meta]) => {
    const s = data.value?.checks?.[key]
    // Show the latest real verdict; mention a newer pending or error run separately.
    const shown = s?.latestCompleted ?? s?.latest ?? null
    const newer = s?.latest && s.latestCompleted && s.latest.ranAtMs > s.latestCompleted.ranAtMs && s.latest.status !== 'OK'
      ? s.latest : null
    const history = (s?.recent ?? []).slice(0, 12).reverse()
    return { key, ...meta, shown, newer, history }
  }),
)
const windowLabel = (r: Result) =>
  r.windowEndMs ? `${formatClock(r.windowStartMs)}–${formatClock(r.windowEndMs)}` : ''
</script>

<template>
  <section class="card" aria-labelledby="audit-title">
    <h2 id="audit-title">Audit · continuous reconciliation</h2>
    <p v-if="!reachable" class="muted">
      Needs the server routes from the Docker stack (<code>docker compose up</code>); not available on the static site.
    </p>
    <p v-else-if="data && !data.available" class="muted">The audit service is not reachable yet. <code>{{ data.error }}</code></p>
    <p v-else-if="data && !data.caughtUp" class="muted">The audit service is reading the topics from the beginning…</p>
    <ul v-else-if="data" class="checks">
      <li v-for="r in rows" :key="r.key">
        <div class="title">
          <strong>{{ r.title }}</strong>
          <span v-if="r.shown" :class="['status', STATUS[r.shown.status].tone]">
            <span class="icon" aria-hidden="true">{{ STATUS[r.shown.status].icon }}</span>
            {{ STATUS[r.shown.status].label }}
          </span>
        </div>
        <p class="muted what">{{ r.what }}</p>
        <template v-if="r.shown">
          <p class="summary">{{ r.shown.summary }}</p>
          <p class="muted meta">
            <template v-if="windowLabel(r.shown)">Window {{ windowLabel(r.shown) }} · </template>checked {{ formatClock(r.shown.ranAtMs) }}
          </p>
          <ul v-if="r.shown.examples.length" class="examples">
            <li v-for="e in r.shown.examples" :key="e"><code>{{ e }}</code></li>
          </ul>
        </template>
        <p v-if="r.newer" :class="['meta', STATUS[r.newer.status].tone]">
          <span aria-hidden="true">{{ STATUS[r.newer.status].icon }}</span> Latest run: {{ r.newer.summary }}
        </p>
        <div v-if="r.history.length" class="history" :aria-label="`Last ${r.history.length} runs`">
          <span v-for="(h, i) in r.history" :key="i" :class="['tick', STATUS[h.status].tone]"
            :title="`${formatClock(h.ranAtMs)}: ${STATUS[h.status].label}`" />
        </div>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.checks {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 260px), 1fr));
  gap: 12px;
}
.checks > li {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 12px;
  min-width: 0;
}
.title {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
}
.status {
  font-size: 13px;
  white-space: nowrap;
}
.status .icon {
  font-weight: 700;
}
.good {
  color: var(--good);
}
.critical {
  color: var(--critical);
}
.warning {
  color: var(--ink-2);
}
.warning > span,
.warning .icon {
  color: var(--warning);
}
.muted {
  color: var(--ink-muted);
}
p {
  margin: 6px 0 0;
}
.what,
.meta {
  font-size: 12px;
}
.summary {
  font-size: 13px;
}
.examples {
  margin: 6px 0 0;
  padding-left: 16px;
  font-size: 12px;
  overflow-wrap: anywhere;
}
.history {
  display: flex;
  gap: 3px;
  margin-top: 10px;
}
.tick {
  width: 10px;
  height: 10px;
  border-radius: 2px;
  background: var(--grid);
}
.tick.good {
  background: var(--good);
}
.tick.critical {
  background: var(--critical);
}
.tick.warning {
  background: var(--warning);
}
</style>
