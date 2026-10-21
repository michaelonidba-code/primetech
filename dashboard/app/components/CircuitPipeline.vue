<script setup lang="ts">
// Decorative circuit of the six pipeline stages with light pulses travelling between them.
// Pure SVG + CSS, so it obeys prefers-reduced-motion (main.css) and costs no network requests.
const stages = ['Ad server', 'Player', 'Gateway', 'Kafka', 'Flink', 'Druid']
const x = (i: number) => 50 + i * 100
// A trace that jogs up and down between nodes, like a printed circuit.
const trace =
  'M50 50 H82 L92 40 H108 L118 50 H182 L192 60 H208 L218 50 H282 L292 40 H308 L318 50 ' +
  'H382 L392 60 H408 L418 50 H482 L492 40 H508 L518 50 H550'
</script>

<template>
  <div class="circuit" role="img" :aria-label="`Live pipeline: ${stages.join(' to ')}`">
    <svg viewBox="0 0 600 104" preserveAspectRatio="xMidYMid meet" aria-hidden="true">
      <defs>
        <filter id="cp-glow" x="-50%" y="-50%" width="200%" height="200%">
          <feGaussianBlur stdDeviation="3" result="b" />
          <feMerge><feMergeNode in="b" /><feMergeNode in="SourceGraphic" /></feMerge>
        </filter>
      </defs>
      <path class="base" :d="trace" pathLength="600" />
      <path class="pulse a" :d="trace" pathLength="600" filter="url(#cp-glow)" />
      <path class="pulse b" :d="trace" pathLength="600" filter="url(#cp-glow)" />
      <g v-for="(s, i) in stages" :key="s" class="node" :style="{ '--i': i }">
        <circle class="ring" :cx="x(i)" cy="50" r="10" filter="url(#cp-glow)" />
        <circle class="core" :cx="x(i)" cy="50" r="3.5" />
        <text :x="x(i)" y="88">{{ s }}</text>
      </g>
    </svg>
  </div>
</template>

<style scoped>
.circuit {
  width: 100%;
  max-width: 640px;
  margin: 0 auto;
}
svg {
  display: block;
  width: 100%;
  height: auto;
  overflow: visible;
}
.base {
  fill: none;
  stroke: rgba(0, 229, 255, 0.28);
  stroke-width: 2;
}
.pulse {
  fill: none;
  stroke-width: 3;
  stroke-linecap: round;
  stroke-dasharray: 46 554;
  stroke-dashoffset: 600;
  animation: run 3.6s linear infinite;
}
.pulse.a {
  stroke: var(--series-1);
}
.pulse.b {
  stroke: var(--accent-2);
  animation-delay: -1.8s;
}
@keyframes run {
  to { stroke-dashoffset: -46; }
}
.ring {
  fill: var(--page);
  stroke: var(--series-1);
  stroke-width: 2;
}
.core {
  fill: var(--series-1);
  animation: blink 3.6s linear infinite;
  animation-delay: calc(var(--i) * 0.6s - 0.2s);
}
@keyframes blink {
  0%, 12%, 100% { fill: var(--series-1); r: 3.5; }
  4% { fill: #fff; r: 5.5; }
}
text {
  fill: var(--ink-2);
  font-family: var(--display);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-anchor: middle;
  text-transform: uppercase;
}
</style>
