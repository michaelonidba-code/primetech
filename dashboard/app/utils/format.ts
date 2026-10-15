const compact = new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 })
const whole = new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 })

export function formatCompact(n: number): string {
  return compact.format(n)
}

export function formatWhole(n: number): string {
  return whole.format(n)
}

export function formatPercent(fraction: number, digits = 2): string {
  return `${(fraction * 100).toFixed(digits)}%`
}

export function formatClock(ms: number): string {
  return new Date(ms).toLocaleTimeString('en-US', { hour12: false })
}

/** Evenly spaced axis ticks from 0 with a "nice" step (1, 2, 2.5 or 5 times a power of ten). */
export function niceTicks(max: number, count = 4): number[] {
  const raw = Math.max(max, 1) / count
  const pow = 10 ** Math.floor(Math.log10(raw))
  const step = ([1, 2, 2.5, 5, 10].find((s) => s * pow >= raw) ?? 10) * pow
  const ticks: number[] = []
  for (let v = 0; v <= max + step * 0.999; v += step) ticks.push(v)
  return ticks.length > 1 ? ticks : [0, step]
}
