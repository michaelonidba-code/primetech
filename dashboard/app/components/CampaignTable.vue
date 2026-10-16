<script setup lang="ts">
interface CampaignRow {
  advertiserName: string
  campaignName: string
  campaignId: string
  impressions: number
  completes: number
  spendMicros: number
  ivtEvents: number
  events: number
}
interface Campaigns {
  available: boolean
  minutes: number
  rows?: CampaignRow[]
  error?: string
}

const { data, reachable } = usePolled<Campaigns>('/api/campaigns?minutes=60', 10_000)
const rows = computed(() => data.value?.rows ?? [])
const maxSpend = computed(() => Math.max(1, ...rows.value.map((r) => r.spendMicros)))
</script>

<template>
  <section class="card">
    <h2>Campaign delivery · last {{ data?.minutes ?? 60 }} minutes · Druid</h2>
    <p v-if="!reachable" class="muted">
      Needs the server routes from the Docker stack (<code>docker compose up</code>); not available on the static site.
    </p>
    <p v-else-if="data && !data.available" class="muted">Druid is not reachable yet. <code>{{ data.error }}</code></p>
    <p v-else-if="data && !rows.length" class="muted">No delivery in this period.</p>
    <div v-else-if="rows.length" class="scroll">
      <table>
        <thead>
          <tr>
            <th>Campaign</th>
            <th class="num">Impressions</th>
            <th class="spend">Spend</th>
            <th class="num">Completion</th>
            <th class="num">IVT</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in rows" :key="r.campaignId">
            <td>
              <div>{{ r.campaignName }}</div>
              <div class="muted small">{{ r.advertiserName }}</div>
            </td>
            <td class="num">{{ formatWhole(r.impressions) }}</td>
            <td class="spend">
              <div class="spend-cell">
                <span class="track" aria-hidden="true">
                  <span class="bar" :style="{ width: `${(r.spendMicros / maxSpend) * 100}%` }" />
                </span>
                <span class="value">{{ formatUsd(r.spendMicros) }}</span>
              </div>
            </td>
            <td class="num">{{ r.impressions ? formatPercent(r.completes / r.impressions, 1) : '—' }}</td>
            <td class="num">{{ r.events ? formatPercent(r.ivtEvents / r.events, 1) : '—' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
p {
  margin: 0;
}
.scroll {
  overflow-x: auto;
}
table {
  width: 100%;
  min-width: 480px;
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
  padding: 6px 8px;
  border-bottom: 1px solid var(--grid);
  vertical-align: middle;
}
.num {
  text-align: right;
  white-space: nowrap;
}
.small {
  font-size: 12px;
}
.spend {
  width: 30%;
  min-width: 140px;
}
.spend-cell {
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
  width: 56px;
  text-align: right;
  white-space: nowrap;
}
</style>
