// The last notifications sent, for the app's alerts panel. In memory: the source of truth for
// active alerts is Alertmanager; this is only recent history.
export interface SentNotification {
  status: 'firing' | 'resolved'
  alertname: string
  severity: string
  summary: string
  atMs: number
}

const recent: SentNotification[] = []

export function rememberNotification(n: SentNotification) {
  recent.unshift(n)
  recent.length = Math.min(recent.length, 30)
}

export function recentNotifications(): SentNotification[] {
  return [...recent]
}
