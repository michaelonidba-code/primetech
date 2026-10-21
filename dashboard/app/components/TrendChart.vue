<script setup lang="ts">
// A small glowing line chart for channel growth over time (one series).
const props = defineProps<{ title: string; points: { t: string; v: number }[]; color?: string }>()

const W = 600
const H = 150
const PAD = { l: 4, r: 8, t: 10, b: 4 }

const view = computed(() => {
  const pts = props.points.filter((p) => Number.isFinite(p.v))
  if (pts.length < 2) return null
  const t0 = Date.parse(pts[0]!.t)
  const t1 = Date.parse(pts.at(-1)!.t)
  const vs = pts.map((p) => p.v)
  let lo = Math.min(...vs)
  let hi = Math.max(...vs)
  if (hi === lo) {
    hi += 1
    lo = Math.max(0, lo - 1)
  }
  const x = (t: string) => PAD.l + ((Date.parse(t) - t0) / Math.max(t1 - t0, 1)) * (W - PAD.l - PAD.r)
  const y = (v: number) => PAD.t + (1 - (v - lo) / (hi - lo)) * (H - PAD.t - PAD.b)
  const line = pts.map((p, i) => `${i ? 'L' : 'M'}${x(p.t).toFixed(1)} ${y(p.v).toFixed(1)}`).join(' ')
  const area = `${line} L${x(pts.at(-1)!.t).toFixed(1)} ${H - PAD.b} L${x(pts[0]!.t).toFixed(1)} ${H - PAD.b} Z`
  const first = pts[0]!
  const last = pts.at(-1)!
  return {
    line,
    area,
    lastX: x(last.t),
    lastY: y(last.v),
    hi,
    lo,
    from: new Date(first.t).toLocaleDateString('en-US', { month: 'short', day: 'numeric' }),
    to: new Date(last.t).toLocaleDateString('en-US', { month: 'short', day: 'numeric' }),
    change: last.v - first.v,
  }
})
const stroke = computed(() => props.color ?? 'var(--series-1)')
const id = useId()
</script>

<template>
  <section class="card trend">
    <h2>
      {{ title }}
      <span v-if="view" class="change" :class="{ up: view.change > 0 }">
        {{ view.change >= 0 ? '+' : '' }}{{ formatWhole(view.change) }} since {{ view.from }}
      </span>
    </h2>
    <p v-if="!view" class="muted empty">
      The chart fills in as the console records the channel every hour.
    </p>
    <svg v-else :viewBox="`0 0 ${W} ${H}`" preserveAspectRatio="none" role="img" :aria-label="`${title} from ${view.from} to ${view.to}`">
      <defs>
        <linearGradient :id="`${id}-fill`" x1="0" x2="0" y1="0" y2="1">
          <stop offset="0%" :stop-color="stroke" stop-opacity="0.35" />
          <stop offset="100%" :stop-color="stroke" stop-opacity="0" />
        </linearGradient>
      </defs>
      <line :x1="PAD.l" :x2="W - PAD.r" :y1="H - PAD.b" :y2="H - PAD.b" class="axis" />
      <path :d="view.area" :fill="`url(#${id}-fill)`" />
      <path :d="view.line" fill="none" :stroke="stroke" stroke-width="2" vector-effect="non-scaling-stroke" class="line" />
    </svg>
    <div v-if="view" class="ticks"><span>{{ view.from }}</span><span>{{ view.to }}</span></div>
  </section>
</template>

<style scoped>
h2 {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}
.change {
  font-family: var(--mono);
  text-transform: none;
  letter-spacing: 0;
  color: var(--ink-muted);
  text-shadow: none;
}
.change.up {
  color: var(--good);
}
svg {
  display: block;
  width: 100%;
  height: 150px;
  overflow: visible;
}
.axis {
  stroke: var(--grid);
}
.line {
  filter: drop-shadow(0 0 4px rgba(0, 229, 255, 0.6));
}
.dot {
  vector-effect: non-scaling-stroke;
}
.ticks {
  display: flex;
  justify-content: space-between;
  margin-top: 6px;
  color: var(--ink-muted);
  font: 11px var(--mono);
}
.empty {
  margin: 0;
}
</style>
