<script setup lang="ts">
type Status = 'ON_PACE' | 'AHEAD' | 'BEHIND' | 'UNDER_DELIVERING' | 'EXHAUSTED'
interface LineItem {
  lineItemId: string
  campaignId: string | null
  tier: 'GUARANTEED' | 'STANDARD' | null
  budgetMicros: number
  deliveredMicros: number
  targetMicros: number
  throttle: number
  status: Status
}
interface PacingStats {
  available: boolean
  error?: string
  periodStartMs: number
  periodEndMs: number
  elapsed: number
  lineItems: LineItem[]
}

const { data, reachable } = usePolled<PacingStats>('/api/pacing', 5000)

// Status uses the reserved status colours, always with an icon and a label.
const STATUS: Record<Status, { label: string; icon: string; tone: string }> = {
  ON_PACE: { label: 'On pace', icon: '✓', tone: 'good' },
  AHEAD: { label: 'Ahead', icon: '▲', tone: 'warning' },
  BEHIND: { label: 'Behind', icon: '▼', tone: 'warning' },
  UNDER_DELIVERING: { label: 'Under-delivering', icon: '!', tone: 'critical' },
  EXHAUSTED: { label: 'Budget spent', icon: '■', tone: 'muted' },
}

const items = computed(() => data.value?.lineItems ?? [])
const counts = computed(() => {
  const c: Partial<Record<Status, number>> = {}
  for (const li of items.value) c[li.status] = (c[li.status] ?? 0) + 1
  return c
})
const totals = computed(() => {
  let budget = 0, delivered = 0, target = 0
  for (const li of items.value) {
    budget += li.budgetMicros
    delivered += li.deliveredMicros
    target += li.targetMicros
  }
  return { budget, delivered, target }
})
const rows = computed(() =>
  [...items.value]
    .sort((a, b) => (a.tier === b.tier ? b.budgetMicros - a.budgetMicros : a.tier === 'GUARANTEED' ? -1 : 1))
    .slice(0, 12),
)
const pct = (part: number, whole: number) => (whole > 0 ? Math.min(100, (part / whole) * 100) : 0)
</script>

<template>
  <section class="card">
    <h2>Budget pacing</h2>
    <p v-if="!reachable" class="muted">
      Needs the server routes from the Docker stack (<code>docker compose up</code>); not available on the static site.
    </p>
    <p v-else-if="data && !data.available" class="muted">The pacing service is not reachable yet. <code>{{ data.error }}</code></p>
    <p v-else-if="data && !items.length" class="muted">No line items yet: waiting for the ad server to publish budgets.</p>
    <template v-else-if="data">
      <div class="tiles">
        <StatTile
          label="Period elapsed"
          :value="formatPercent(data.elapsed, 0)"
          :detail="`${formatClock(data.periodStartMs)}–${formatClock(data.periodEndMs)}`"
        />
        <StatTile
          label="Delivered / budget"
          :value="formatPercent(totals.delivered / (totals.budget || 1), 1)"
          :detail="`${formatUsd(totals.delivered)} of ${formatUsd(totals.budget)} · target ${formatPercent(totals.target / (totals.budget || 1), 0)}`"
        />
        <div class="card status-tile">
          <div class="label">Line items by status</div>
          <ul>
            <li v-for="(meta, key) in STATUS" :key="key" :class="meta.tone">
              <span class="icon" aria-hidden="true">{{ meta.icon }}</span>
              <span>{{ meta.label }}</span>
              <strong>{{ counts[key] ?? 0 }}</strong>
            </li>
          </ul>
        </div>
      </div>

      <div class="legend muted">
        <span><span class="swatch bar-swatch" aria-hidden="true" /> delivered</span>
        <span><span class="swatch target-swatch" aria-hidden="true" /> even-pacing target</span>
        <span>bar length = budget</span>
      </div>
      <div class="scroll">
        <table>
          <thead>
            <tr>
              <th>Line item</th>
              <th class="bullet-col">Delivered vs target</th>
              <th class="num">Budget</th>
              <th class="num">Throttle</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="li in rows" :key="li.lineItemId">
              <td>
                <code>{{ li.lineItemId }}</code>
                <span v-if="li.tier === 'GUARANTEED'" class="tier">guaranteed</span>
              </td>
              <td class="bullet-col">
                <div
                  class="bullet"
                  :title="`${formatUsd(li.deliveredMicros)} delivered, target ${formatUsd(li.targetMicros)}`"
                >
                  <span class="fill" :style="{ width: `${pct(li.deliveredMicros, li.budgetMicros)}%` }" />
                  <span class="target" :style="{ left: `${pct(li.targetMicros, li.budgetMicros)}%` }" />
                </div>
              </td>
              <td class="num">{{ formatUsd(li.budgetMicros) }}</td>
              <td class="num">{{ li.throttle >= 0.1 ? li.throttle.toFixed(2) : li.throttle.toFixed(3) }}</td>
              <td :class="['status', STATUS[li.status].tone]">
                <span class="icon" aria-hidden="true">{{ STATUS[li.status].icon }}</span>
                {{ STATUS[li.status].label }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-if="items.length > rows.length" class="muted more">
        Showing {{ rows.length }} of {{ items.length }} line items: guaranteed first, then by budget.
      </p>
    </template>
  </section>
</template>

<style scoped>
p {
  margin: 0;
}
.tiles {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}
.tiles :deep(.card),
.status-tile {
  background: var(--page);
}
.status-tile .label {
  color: var(--ink-2);
  font-size: 13px;
  margin-bottom: 6px;
}
.status-tile ul {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 2px;
  font-size: 13px;
}
.status-tile li {
  display: grid;
  grid-template-columns: 16px 1fr auto;
  gap: 6px;
  color: var(--ink-2);
}
.icon {
  font-weight: 700;
  text-align: center;
}
.good .icon {
  color: var(--good);
}
.warning .icon {
  color: var(--warning);
}
.critical .icon {
  color: var(--critical);
}
.muted .icon {
  color: var(--ink-muted);
}
.legend {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  font-size: 12px;
  margin-bottom: 6px;
}
.swatch {
  display: inline-block;
  vertical-align: middle;
  margin-right: 4px;
}
.bar-swatch {
  width: 14px;
  height: 8px;
  background: var(--series-1);
  border-radius: 2px;
}
.target-swatch {
  width: 2px;
  height: 12px;
  background: var(--ink-1);
}
.scroll {
  overflow-x: auto;
}
table {
  width: 100%;
  min-width: 560px;
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
  white-space: nowrap;
}
.num {
  text-align: right;
}
.tier {
  margin-left: 6px;
  font-size: 11px;
  color: var(--ink-2);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 0 4px;
}
.bullet-col {
  width: 36%;
}
.bullet {
  position: relative;
  height: 12px;
  background: var(--grid);
  border-radius: 0 4px 4px 0;
}
.fill {
  position: absolute;
  inset: 0 auto 0 0;
  background: var(--series-1);
  border-radius: 0 4px 4px 0;
}
.target {
  position: absolute;
  top: -3px;
  bottom: -3px;
  width: 2px;
  margin-left: -1px;
  background: var(--ink-1);
}
.status {
  color: var(--ink-2);
}
.more {
  margin-top: 8px;
  font-size: 12px;
}
</style>
