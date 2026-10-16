<script setup lang="ts">
import type { RatePoint } from '~/composables/useGatewayStats'

const props = defineProps<{ points: RatePoint[] }>()

const H = 220
const M = { top: 12, right: 12, bottom: 24, left: 44 }
const innerH = H - M.top - M.bottom
const WINDOW_MS = 5 * 60 * 1000

// Draw at the container's real pixel width so text and strokes keep their size on any screen.
const wrap = ref<HTMLElement | null>(null)
const W = ref(640)
const innerW = computed(() => W.value - M.left - M.right)
let observer: ResizeObserver | undefined
onMounted(() => {
  observer = new ResizeObserver(([entry]) => {
    if (entry) W.value = Math.max(240, Math.round(entry.contentRect.width))
  })
  if (wrap.value) observer.observe(wrap.value)
})
onBeforeUnmount(() => observer?.disconnect())

const hover = ref<number | null>(null)

const domain = computed(() => {
  const end = props.points.at(-1)?.t ?? Date.now()
  return { start: end - WINDOW_MS, end }
})
const yTicks = computed(() => niceTicks(Math.max(1, ...props.points.map((p) => p.eps)) * 1.05))
const yMax = computed(() => yTicks.value.at(-1)!)

const x = (t: number) => M.left + ((t - domain.value.start) / WINDOW_MS) * innerW.value
const y = (v: number) => M.top + innerH - (v / yMax.value) * innerH

const path = computed(() =>
  props.points.map((p, i) => `${i === 0 ? 'M' : 'L'}${x(p.t).toFixed(1)},${y(p.eps).toFixed(1)}`).join(''),
)
const xTicks = computed(() => {
  // One tick per minute, or every other minute when narrow.
  const step = W.value < 420 ? 2 : 1
  const ticks = []
  for (let m = 5; m >= 0; m -= step) ticks.push(domain.value.end - m * 60_000)
  if (ticks.at(-1) !== domain.value.end) ticks.push(domain.value.end)
  return ticks
})

function onMove(evt: PointerEvent) {
  if (!props.points.length) return
  const svg = evt.currentTarget as SVGSVGElement
  const rect = svg.getBoundingClientRect()
  const px = evt.clientX - rect.left
  let best = 0
  for (let i = 1; i < props.points.length; i++) {
    if (Math.abs(x(props.points[i]!.t) - px) < Math.abs(x(props.points[best]!.t) - px)) best = i
  }
  hover.value = best
}

const hovered = computed(() => (hover.value === null ? null : props.points[hover.value] ?? null))
const tooltipLeft = computed(() => {
  if (!hovered.value) return '0px'
  // Keep the tooltip inside the card near either edge.
  return `${Math.min(Math.max(x(hovered.value.t), 70), W.value - 70)}px`
})
</script>

<template>
  <div ref="wrap" class="wrap">
    <svg
      :width="W"
      :height="H"
      :viewBox="`0 0 ${W} ${H}`"
      role="img"
      aria-label="Accepted events per second over the last five minutes"
      @pointermove="onMove"
      @pointerleave="hover = null"
    >
      <g v-for="v in yTicks" :key="v">
        <line :x1="M.left" :x2="W - M.right" :y1="y(v)" :y2="y(v)" :class="v === 0 ? 'baseline' : 'grid'" />
        <text :x="M.left - 8" :y="y(v)" class="tick" text-anchor="end" dominant-baseline="middle">
          {{ formatCompact(v) }}
        </text>
      </g>
      <text
        v-for="t in xTicks"
        :key="t"
        :x="x(t)"
        :y="H - 6"
        class="tick"
        :text-anchor="t === xTicks[0] ? 'start' : t === xTicks.at(-1) ? 'end' : 'middle'"
      >
        {{ t === xTicks.at(-1) ? 'now' : `-${Math.round((domain.end - t) / 60000)}m` }}
      </text>
      <path :d="path" class="line" />
      <g v-if="hovered">
        <line :x1="x(hovered.t)" :x2="x(hovered.t)" :y1="M.top" :y2="M.top + innerH" class="crosshair" />
        <circle :cx="x(hovered.t)" :cy="y(hovered.eps)" r="4" class="dot" />
      </g>
      <rect :x="M.left" :y="M.top" :width="innerW" :height="innerH" fill="transparent" />
    </svg>
    <div v-if="hovered" class="tooltip" :style="{ left: tooltipLeft }">
      <strong>{{ formatWhole(hovered.eps) }}</strong> events/s
      <div class="muted">{{ formatClock(hovered.t) }}</div>
    </div>
    <p v-if="points.length < 2" class="empty muted">Waiting for data…</p>
  </div>
</template>

<style scoped>
.wrap {
  position: relative;
}
svg {
  display: block;
  touch-action: none;
}
.grid {
  stroke: var(--grid);
  stroke-width: 1;
}
.baseline {
  stroke: var(--axis);
  stroke-width: 1;
}
.tick {
  fill: var(--ink-muted);
  font-size: 11px;
}
.line {
  fill: none;
  stroke: var(--series-1);
  stroke-width: 2;
  stroke-linejoin: round;
  stroke-linecap: round;
}
.crosshair {
  stroke: var(--ink-muted);
  stroke-dasharray: 3 3;
}
.dot {
  fill: var(--series-1);
  stroke: var(--surface);
  stroke-width: 2;
}
.tooltip {
  position: absolute;
  top: 0;
  transform: translateX(-50%);
  background: var(--tooltip-bg);
  color: var(--tooltip-ink);
  padding: 6px 10px;
  border-radius: 6px;
  font-size: 12px;
  pointer-events: none;
  white-space: nowrap;
}
.tooltip .muted {
  color: inherit;
  opacity: 0.7;
}
.empty {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  margin: 0;
}
</style>
