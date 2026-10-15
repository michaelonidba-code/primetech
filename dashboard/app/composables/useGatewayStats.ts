export interface GatewaySnapshot {
  timestampMs: number
  uptimeSeconds: number
  accepted: number
  rejected: number
  batches: number
  publishFailures: number
  acceptedByType: Record<string, number>
  rejectedByReason: Record<string, number>
  latencyP50Ms: number
  latencyP99Ms: number
}

export interface RatePoint {
  t: number
  eps: number
}

const HISTORY_POINTS = 150 // 5 minutes at the default 2s poll

/**
 * Polls the gateway's counters and derives event rates from successive snapshots: through this
 * server's /api/gateway (Node builds), or from the gateway directly (static build, ?gateway=<url>).
 */
export function useGatewayStats() {
  const config = useRuntimeConfig().public
  const route = useRoute()
  const session = useSession()
  const direct = computed(() => Boolean(route.query.gateway) || config.gatewayMode === 'direct')
  const gatewayUrl = computed(() =>
    String(route.query.gateway || config.gatewayUrl).replace(/\/+$/, ''),
  )
  const statsUrl = computed(() => (direct.value ? `${gatewayUrl.value}/stats` : '/api/gateway'))

  const latest = ref<GatewaySnapshot | null>(null)
  const history = ref<RatePoint[]>([])
  const status = ref<'connecting' | 'up' | 'down'>('connecting')
  const lastError = ref('')
  let previous: GatewaySnapshot | null = null

  async function poll() {
    try {
      const snap = await $fetch<GatewaySnapshot>(statsUrl.value, { timeout: 2000 })
      if (previous) {
        const dt = (snap.timestampMs - previous.timestampMs) / 1000
        const delta = snap.accepted - previous.accepted
        // A negative delta means the gateway restarted and its counters reset: skip that point.
        if (dt > 0 && delta >= 0) {
          history.value = [...history.value, { t: snap.timestampMs, eps: delta / dt }].slice(-HISTORY_POINTS)
        }
      }
      previous = snap
      latest.value = snap
      status.value = 'up'
      lastError.value = ''
    } catch (err) {
      status.value = 'down'
      lastError.value = err instanceof Error ? err.message : String(err)
      session.noteError(err)
    }
  }

  useVisibleInterval(poll, Number(config.pollMs))

  const currentEps = computed(() => history.value.at(-1)?.eps ?? 0)
  const rejectionRate = computed(() => {
    const s = latest.value
    if (!s || s.accepted + s.rejected === 0) return 0
    return s.rejected / (s.accepted + s.rejected)
  })

  return { direct, gatewayUrl, latest, history, status, lastError, currentEps, rejectionRate }
}
