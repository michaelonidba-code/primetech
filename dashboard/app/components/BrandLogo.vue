<script setup lang="ts">
// The Quila Visuals logo. The PNG is used as a mask so the logo takes the current text colour and
// follows the light and dark themes from a single file.
withDefaults(defineProps<{ height?: number }>(), { height: 44 })
const src = `${useRuntimeConfig().app.baseURL}brand-logo.png`
</script>

<template>
  <span
    class="brand-logo"
    role="img"
    aria-label="Quila Visuals"
    :style="{ height: `${height}px`, '--logo': `url(${src})` }"
  />
</template>

<style scoped>
.brand-logo {
  display: inline-block;
  /* brand-logo.png is 686 × 746. */
  aspect-ratio: 686 / 746;
  flex: none;
  background: var(--ink-1);
  -webkit-mask: var(--logo) center / contain no-repeat;
  mask: var(--logo) center / contain no-repeat;
}
</style>
