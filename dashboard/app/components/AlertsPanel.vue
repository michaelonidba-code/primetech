<script setup lang="ts">
interface ActiveAlert {
  id: string
  alertname: string
  severity: 'critical' | 'warning' | string
  check: string | null
  summary: string
  description: string
  startsAt: string
}
interface Sent {
  status: 'firing' | 'resolved'
  alertname: string
  severity: string
  summary: string
  atMs: number
}
interface AlertsResponse {
  available: boolean
  error?: string
  push?: { enabled: boolean; devices: number }
  active?: ActiveAlert[]
  recent?: Sent[]
}

const { data, reachable } = usePolled<AlertsResponse>('/api/alerts', 10_000)
const push = usePush()
const now = ref(Date.now())
useVisibleInterval(() => (now.value = Date.now()), 10_000)

const active = computed(() => data.value?.active ?? [])
const recent = computed(() => (data.value?.recent ?? []).slice(0, 5))
const since = (iso: string) => formatDuration(Math.max(0, now.value - Date.parse(iso)))

const PUSH_TEXT: Record<string, string> = {
  checking: '',
  unsupported: 'This browser cannot receive notifications. On iPhone, add the app to the Home Screen first.',
  unavailable: 'Notifications are not configured on the server (VAPID keys).',
  blocked: 'Notifications are blocked for this site in the browser settings.',
  off: 'Get critical and warning alerts on this device, even when the app is closed.',
  on: 'This device gets a notification when an alert fires or resolves.',
}
</script>

<template>
  <section id="alerts" class="card" aria-labelledby="alerts-title">
    <div class="head">
      <h2 id="alerts-title">Alerts</h2>
      <div v-if="reachable && data?.available" class="push">
        <span class="muted">{{ PUSH_TEXT[push.state.value] }}</span>
        <button v-if="push.state.value === 'off'" type="button" :disabled="push.busy.value" @click="push.enable()">
          {{ push.busy.value ? 'Turning on…' : 'Turn on notifications' }}
        </button>
        <button v-else-if="push.state.value === 'on'" type="button" class="ghost" :disabled="push.busy.value"
          @click="push.disable()">Turn off</button>
      </div>
    </div>
    <p v-if="push.error.value" class="critical-text" role="alert">▲ {{ push.error.value }}</p>

    <p v-if="!reachable" class="muted">
      Needs the server routes from the Docker stack (<code>docker compose up</code>); not available on the static site.
    </p>
    <p v-else-if="data && !data.available" class="muted">Alertmanager is not reachable. <code>{{ data.error }}</code></p>
    <template v-else-if="data">
      <p v-if="!active.length" class="all-clear"><span class="icon good" aria-hidden="true">✓</span> No active alerts.</p>
      <ul v-else class="alerts">
        <li v-for="a in active" :key="a.id" :class="a.severity">
          <span class="icon" aria-hidden="true">{{ a.severity === 'critical' ? '!' : '▲' }}</span>
          <div>
            <strong>{{ a.summary }}</strong>
            <span class="muted"> · {{ a.severity }} · for {{ since(a.startsAt) }}</span>
            <p class="muted">{{ a.description }}</p>
          </div>
        </li>
      </ul>
      <details v-if="recent.length" class="recent">
        <summary class="muted">Recent notifications</summary>
        <ul>
          <li v-for="(n, i) in recent" :key="i" class="muted">
            {{ formatClock(n.atMs) }} · {{ n.status === 'firing' ? 'Fired' : 'Resolved' }}: {{ n.summary }}
          </li>
        </ul>
      </details>
    </template>
  </section>
</template>

<style scoped>
.head {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: baseline;
  gap: 8px 16px;
}
.head h2 {
  margin-bottom: 8px;
}
.push {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}
button {
  font: inherit;
  font-weight: 600;
  min-height: 36px;
  padding: 6px 12px;
  border: 0;
  border-radius: 8px;
  background: var(--series-1);
  color: #fff;
  cursor: pointer;
}
button.ghost {
  font-weight: 400;
  border: 1px solid var(--axis);
  background: var(--surface);
  color: var(--ink-1);
}
button:focus-visible {
  outline: 2px solid var(--series-1);
  outline-offset: 2px;
}
.all-clear {
  margin: 4px 0 0;
}
.icon.good {
  color: var(--good);
  font-weight: 700;
}
.alerts {
  list-style: none;
  margin: 4px 0 0;
  padding: 0;
  display: grid;
  gap: 10px;
}
.alerts li {
  display: grid;
  grid-template-columns: 20px 1fr;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 8px;
  border: 1px solid var(--border);
  border-left: 4px solid var(--warning);
}
.alerts li.critical {
  border-left-color: var(--critical);
}
.alerts li .icon {
  font-weight: 700;
  color: var(--warning);
}
.alerts li.critical .icon {
  color: var(--critical);
}
.alerts p {
  margin: 4px 0 0;
  font-size: 13px;
}
.critical-text {
  color: var(--critical);
  font-size: 13px;
}
.recent {
  margin-top: 12px;
  font-size: 13px;
}
.recent ul {
  margin: 6px 0 0;
  padding-left: 18px;
}
</style>
