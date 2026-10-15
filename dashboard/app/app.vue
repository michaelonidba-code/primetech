<script setup lang="ts">
const { gatewayUrl, latest, history, status, lastError, currentEps, rejectionRate } = useGatewayStats()

const rejectionRows = computed(() =>
  Object.entries(latest.value?.rejectedByReason ?? {})
    .filter(([, n]) => n > 0)
    .sort((a, b) => b[1] - a[1]),
)
</script>

<template>
  <main>
    <header>
      <div>
        <h1>Ads Eventing</h1>
        <p class="muted">Live pipeline · ad server → player → gateway → Kafka → Flink → Druid</p>
      </div>
      <div class="status" :class="status" role="status">
        <span class="icon" aria-hidden="true">{{ status === 'up' ? '●' : status === 'down' ? '▲' : '○' }}</span>
        {{ status === 'up' ? 'Connected' : status === 'down' ? 'Gateway unreachable' : 'Connecting' }}
        <code class="muted">{{ gatewayUrl }}</code>
      </div>
    </header>

    <section v-if="status === 'down' && !latest" class="card notice">
      <h2>Can't reach the gateway</h2>
      <p>
        Start the stack with <code>docker compose up --build</code>, or point the dashboard at another gateway
        with <code>?gateway=http://host:8080</code>.
      </p>
      <p class="muted"><code>{{ lastError }}</code></p>
    </section>

    <section class="tiles">
      <StatTile label="Accepted events/s" :value="formatWhole(currentEps)" detail="2s window" />
      <StatTile
        label="Events accepted"
        :value="formatCompact(latest?.accepted ?? 0)"
        :detail="`${formatCompact(latest?.batches ?? 0)} batches`"
      />
      <StatTile
        label="Rejection rate"
        :value="formatPercent(rejectionRate)"
        :detail="`${formatCompact(latest?.rejected ?? 0)} sent to DLQ`"
      />
      <StatTile
        label="Request latency p99"
        :value="`${(latest?.latencyP99Ms ?? 0).toFixed(1)} ms`"
        :detail="`p50 ${(latest?.latencyP50Ms ?? 0).toFixed(1)} ms · includes Kafka ack`"
      />
      <StatTile
        label="Publish failures"
        :value="formatWhole(latest?.publishFailures ?? 0)"
        :detail="(latest?.publishFailures ?? 0) === 0 ? 'Kafka healthy' : 'batches returned 503'"
        :tone="(latest?.publishFailures ?? 0) === 0 ? 'good' : 'critical'"
      />
    </section>

    <AdServingPanel />

    <PipelinePanel />

    <section class="card">
      <h2>Accepted events per second · last 5 minutes</h2>
      <ThroughputChart :points="history" />
    </section>

    <div class="split">
      <section class="card">
        <h2>Playback funnel</h2>
        <FunnelChart :by-type="latest?.acceptedByType ?? {}" />
      </section>

      <section class="card">
        <h2>Rejections by reason</h2>
        <table v-if="rejectionRows.length">
          <thead>
            <tr><th>Reason</th><th class="num">Events</th><th class="num">Share</th></tr>
          </thead>
          <tbody>
            <tr v-for="[reason, n] in rejectionRows" :key="reason">
              <td><code>{{ reason }}</code></td>
              <td class="num">{{ formatWhole(n) }}</td>
              <td class="num">{{ formatPercent(n / (latest?.rejected || 1), 1) }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="muted">No rejected events.</p>
      </section>
    </div>

    <CampaignTable />
  </main>
</template>

<style scoped>
main {
  max-width: 1200px;
  margin: 0 auto;
  padding: 24px 16px 48px;
  display: grid;
  gap: 16px;
}
header {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: flex-end;
  gap: 12px;
}
h1 {
  margin: 0;
  font-size: 22px;
}
header p {
  margin: 2px 0 0;
}
.status {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 13px;
  color: var(--ink-2);
}
.status.up .icon {
  color: var(--good);
}
.status.down .icon {
  color: var(--critical);
}
.notice p {
  margin: 0 0 8px;
}
.tiles {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
}
.split {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 16px;
}
table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
th {
  text-align: left;
  font-weight: 500;
  color: var(--ink-muted);
  border-bottom: 1px solid var(--axis);
  padding: 6px 4px;
}
td {
  padding: 6px 4px;
  border-bottom: 1px solid var(--grid);
}
.num {
  text-align: right;
}
</style>
