// Alertmanager's webhook: every firing or resolved alert becomes a push notification on each
// device that turned notifications on, and joins the recent-notifications list.
// Authenticated by a shared bearer token, not a session; Caddy also blocks this path from outside.
import { timingSafeEqual } from 'node:crypto'

interface AmAlert {
  status: 'firing' | 'resolved'
  labels: Record<string, string>
  annotations: Record<string, string>
  startsAt: string
  endsAt: string
  fingerprint: string
}

function tokenMatches(given: string, expected: string) {
  const a = Buffer.from(given)
  const b = Buffer.from(expected)
  return a.length === b.length && timingSafeEqual(a, b)
}

export default defineEventHandler(async (event) => {
  const expected = String(useRuntimeConfig().alertWebhookToken || '')
  if (expected.length < 16) throw createError({ statusCode: 503, statusMessage: 'Alert webhook not configured' })
  const given = (getRequestHeader(event, 'authorization') || '').replace(/^Bearer\s+/i, '')
  if (!tokenMatches(given, expected)) throw createError({ statusCode: 401, statusMessage: 'Bad token' })

  if (Number(getRequestHeader(event, 'content-length') || 0) > 512 * 1024) {
    throw createError({ statusCode: 413, statusMessage: 'Request too large' })
  }
  const body = await readBody<{ alerts?: AmAlert[] }>(event)
  const alerts = Array.isArray(body?.alerts) ? body.alerts.slice(0, 50) : []
  let sent = 0
  for (const a of alerts) {
    const firing = a.status === 'firing'
    const name = a.labels.alertname ?? 'Alert'
    const summary = a.annotations.summary ?? name
    rememberNotification({
      status: a.status,
      alertname: name,
      severity: a.labels.severity ?? 'warning',
      summary,
      atMs: Date.now(),
    })
    const result = await pushToAll({
      title: firing ? `${a.labels.severity === 'critical' ? 'Critical' : 'Warning'}: ${summary}` : `Resolved: ${summary}`,
      body: (firing ? a.annotations.description ?? '' : `${name} is no longer firing.`).slice(0, 300),
      tag: a.fingerprint || name,
      url: '/#alerts',
    })
    sent += result.sent
  }
  return { received: alerts.length, pushed: sent }
})
