// Active alerts from Alertmanager (silenced and inhibited ones left out), plus recent notifications.
interface AmAlert {
  labels: Record<string, string>
  annotations: Record<string, string>
  startsAt: string
  fingerprint: string
}

export default defineEventHandler(async () => {
  const url = useRuntimeConfig().alertmanagerUrl.replace(/\/+$/, '')
  try {
    const alerts = await $fetch<AmAlert[]>(`${url}/api/v2/alerts`, {
      query: { active: true, silenced: false, inhibited: false },
      timeout: 3000,
    })
    return {
      available: true,
      push: { enabled: pushConfig() !== null, devices: await subscriptionCount() },
      active: alerts
        .map((a) => ({
          id: a.fingerprint,
          alertname: a.labels.alertname,
          severity: a.labels.severity ?? 'warning',
          check: a.labels.check ?? null,
          summary: a.annotations.summary ?? a.labels.alertname,
          description: a.annotations.description ?? '',
          startsAt: a.startsAt,
        }))
        .sort((a, b) => (a.severity === b.severity ? a.startsAt.localeCompare(b.startsAt) : a.severity === 'critical' ? -1 : 1)),
      recent: recentNotifications(),
    }
  } catch (err) {
    return { available: false, error: err instanceof Error ? err.message : String(err) }
  }
})
