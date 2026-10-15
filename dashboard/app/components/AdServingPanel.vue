<script setup lang="ts">
interface DspStats {
  requests: number
  bids: number
  noBids: number
  timeouts: number
  errors: number
  wins: number
  latencyP50Ms: number
  latencyP99Ms: number
}
interface AdServerStats {
  available: boolean
  error?: string
  timestampMs: number
  requests: number
  shed: number
  slotsRequested: number
  slotsFilled: number
  frequencyCapped: number
  spendMicros: number
  latencyP50Ms: number
  latencyP99Ms: number
  winsBySource: Record<string, number>
  dsps: Record<string, DspStats>
}

const { data, reachable } = usePolled<AdServerStats>('/api/adserver', 2000)

// Auctions per second from successive counter readings.
const rate = ref(0)
let previous: { t: number; n: number } | null = null
watch(data, (s) => {
  if (!s?.available) return
  if (previous && s.timestampMs > previous.t && s.requests >= previous.n) {
    rate.value = ((s.requests - previous.n) * 1000) / (s.timestampMs - previous.t)
  }
  previous = { t: s.timestampMs, n: s.requests }
})

const fill = computed(() => (data.value?.slotsRequested ? data.value.slotsFilled / data.value.slotsRequested : 0))
const totalWins = computed(() => Object.values(data.value?.winsBySource ?? {}).reduce((a, b) => a + b, 0))
const rows = computed(() => {
  const s = data.value
  if (!s?.available) return []
  const out = Object.entries(s.dsps ?? {}).map(([name, d]) => ({
    name,
    bidRate: d.requests ? (d.requests - d.noBids - d.timeouts - d.errors) / d.requests : 0,
    timeoutRate: d.requests ? d.timeouts / d.requests : 0,
    winShare: totalWins.value ? d.wins / totalWins.value : 0,
    p99: d.latencyP99Ms,
  }))
  out.push({
    name: 'direct (line items)',
    bidRate: NaN,
    timeoutRate: NaN,
    winShare: totalWins.value ? (s.winsBySource.direct ?? 0) / totalWins.value : 0,
    p99: NaN,
  })
  return out.sort((a, b) => b.winShare - a.winShare)
})
</script>

<template>
  <section class="card">
    <h2>Ad serving · OpenRTB auction</h2>
    <p v-if="!reachable" class="muted">
      Needs the server routes from the Docker stack (<code>docker compose up</code>); not available on the static site.
    </p>
    <p v-else-if="data && !data.available" class="muted">The ad server is not reachable yet. <code>{{ data.error }}</code></p>
    <template v-else-if="data">
      <div class="tiles">
        <StatTile label="Auctions/s" :value="formatWhole(rate)" :detail="`${formatCompact(data.requests)} total`" />
        <StatTile label="Fill rate" :value="formatPercent(fill, 1)" detail="slots served / requested" />
        <StatTile
          label="Decision latency p99"
          :value="`${data.latencyP99Ms.toFixed(0)} ms`"
          :detail="`p50 ${data.latencyP50Ms.toFixed(0)} ms · includes DSP wait`"
        />
        <StatTile
          label="Requests shed"
          :value="formatWhole(data.shed)"
          :detail="data.shed === 0 ? 'within capacity' : 'answered 503 under overload'"
          :tone="data.shed === 0 ? 'good' : 'critical'"
        />
        <StatTile label="Frequency-capped" :value="formatCompact(data.frequencyCapped)" detail="candidates removed" />
      </div>
      <div class="scroll">
        <table>
          <thead>
            <tr>
              <th>Demand</th>
              <th class="share">Win share</th>
              <th class="num">Bid rate</th>
              <th class="num">Timeouts</th>
              <th class="num">p99</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in rows" :key="r.name">
              <td><code>{{ r.name }}</code></td>
              <td class="share">
                <div class="share-cell">
                  <span class="track" aria-hidden="true"><span class="bar" :style="{ width: `${r.winShare * 100}%` }" /></span>
                  <span class="value">{{ formatPercent(r.winShare, 1) }}</span>
                </div>
              </td>
              <td class="num">{{ Number.isNaN(r.bidRate) ? '—' : formatPercent(r.bidRate, 0) }}</td>
              <td class="num">{{ Number.isNaN(r.timeoutRate) ? '—' : formatPercent(r.timeoutRate, 1) }}</td>
              <td class="num">{{ Number.isNaN(r.p99) ? '—' : `${r.p99.toFixed(0)} ms` }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </section>
</template>

<style scoped>
p {
  margin: 0;
}
.tiles {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}
.tiles :deep(.card) {
  background: var(--page);
}
.scroll {
  overflow-x: auto;
}
table {
  width: 100%;
  min-width: 460px;
  border-collapse: collapse;
  font-size: 13px;
}
th {
  text-align: left;
  font-weight: 500;
  color: var(--ink-muted);
  border-bottom: 1px solid var(--axis);
  padding: 6px 8px;
  white-space: nowrap;
}
td {
  padding: 8px;
  border-bottom: 1px solid var(--grid);
}
.num {
  text-align: right;
  white-space: nowrap;
}
.share {
  width: 40%;
}
.share-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}
.track {
  flex: 1;
  min-width: 48px;
}
.bar {
  display: block;
  height: 10px;
  min-width: 2px;
  background: var(--series-1);
  border-radius: 0 4px 4px 0;
}
.value {
  width: 48px;
  text-align: right;
  white-space: nowrap;
}
</style>
