<script setup lang="ts">
// Playback funnel: how many impressions went on to each quartile. Bars share one hue because
// they are one series (counts of a single measure), not categories needing identity.
const props = defineProps<{ byType: Record<string, number> }>()

const STEPS = [
  ['IMPRESSION', 'Impression'],
  ['FIRST_QUARTILE', '25%'],
  ['MIDPOINT', '50%'],
  ['THIRD_QUARTILE', '75%'],
  ['COMPLETE', 'Complete'],
  ['CLICK', 'Click'],
] as const

const hover = ref<string | null>(null)

const rows = computed(() => {
  const impressions = props.byType.IMPRESSION ?? 0
  const max = Math.max(1, ...STEPS.map(([k]) => props.byType[k] ?? 0))
  return STEPS.map(([key, label]) => {
    const count = props.byType[key] ?? 0
    return {
      key,
      label,
      count,
      width: (count / max) * 100,
      ofImpressions: impressions > 0 ? count / impressions : 0,
    }
  })
})
</script>

<template>
  <div class="funnel" role="table" aria-label="Accepted events by type">
    <div
      v-for="r in rows"
      :key="r.key"
      class="row"
      role="row"
      @pointerenter="hover = r.key"
      @pointerleave="hover = null"
    >
      <span class="name" role="rowheader">{{ r.label }}</span>
      <span class="track" role="cell">
        <span class="bar" :style="{ width: `${Math.max(r.width, r.count ? 0.5 : 0)}%` }" />
      </span>
      <span class="num" role="cell">
        {{ formatCompact(r.count) }}
        <span v-if="r.key !== 'IMPRESSION'" class="muted">· {{ formatPercent(r.ofImpressions, 1) }}</span>
      </span>
      <span v-if="hover === r.key" class="tip">
        {{ formatWhole(r.count) }} {{ r.label.toLowerCase() }} events
        <template v-if="r.key !== 'IMPRESSION'">({{ formatPercent(r.ofImpressions, 2) }} of impressions)</template>
      </span>
    </div>
  </div>
</template>

<style scoped>
.funnel {
  display: grid;
  gap: 8px;
}
.row {
  position: relative;
  display: grid;
  grid-template-columns: 88px 1fr 128px;
  align-items: center;
  gap: 10px;
  padding: 2px 0;
}
.name {
  color: var(--ink-2);
  font-size: 13px;
}
.track {
  height: 14px;
}
.bar {
  display: block;
  height: 100%;
  background: var(--series-1);
  border-radius: 0 4px 4px 0;
  transition: width 0.4s ease;
}
.row:hover .bar {
  filter: brightness(1.1);
}
.num {
  text-align: right;
  font-size: 13px;
}
.tip {
  position: absolute;
  right: 0;
  bottom: 100%;
  background: var(--tooltip-bg);
  color: var(--tooltip-ink);
  padding: 4px 8px;
  border-radius: 6px;
  font-size: 12px;
  pointer-events: none;
  white-space: nowrap;
  z-index: 1;
}
@media (max-width: 480px) {
  .row {
    grid-template-columns: 72px 1fr 104px;
  }
}
</style>
