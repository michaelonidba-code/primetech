<script setup lang="ts">
defineProps<{
  label: string
  value: string
  detail?: string
  tone?: 'good' | 'critical'
}>()
</script>

<template>
  <div class="card tile">
    <div class="label">{{ label }}</div>
    <div class="value">{{ value }}</div>
    <div v-if="detail" class="detail" :class="tone">
      <span v-if="tone === 'good'" aria-hidden="true">✓</span>
      <span v-else-if="tone === 'critical'" aria-hidden="true">!</span>
      {{ detail }}
    </div>
  </div>
</template>

<style scoped>
.tile {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.label {
  color: var(--ink-2);
  font-size: 12px;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}
.value {
  font-family: var(--display);
  font-size: clamp(20px, 6vw, 26px);
  overflow-wrap: anywhere;
  font-weight: 600;
  letter-spacing: 0.02em;
  color: #f2fdff;
  text-shadow: 0 0 6px rgba(0, 229, 255, 0.7), 0 0 18px rgba(0, 229, 255, 0.35);
}
.detail {
  color: var(--ink-muted);
  font-size: 12px;
}
.detail.good span {
  color: var(--good);
  font-weight: 700;
}
.detail.critical span {
  color: var(--critical);
  font-weight: 700;
}
</style>
