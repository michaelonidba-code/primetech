// Web Push to installed copies of the app: VAPID keys from config, subscriptions kept in a small
// JSON file (one per device that turned notifications on).
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises'
import { dirname } from 'node:path'
import webpush from 'web-push'

export interface StoredSubscription {
  endpoint: string
  keys: { p256dh: string; auth: string }
  user: string | null
  createdAt: number
}

export interface PushPayload {
  title: string
  body: string
  /** Notifications with the same tag replace each other: "resolved" replaces "firing". */
  tag: string
  url: string
}

// The browsers' push services. A subscription endpoint is a URL the server will POST to, so only
// these hosts are accepted, never an arbitrary (possibly internal) address.
const DEFAULT_PUSH_HOSTS = [
  'fcm.googleapis.com', // Chrome, Edge, Android
  'updates.push.services.mozilla.com', // Firefox
  '.push.apple.com', // Safari, iOS 16.4+ home-screen apps
  '.notify.windows.com', // legacy Edge
]

let configured: { publicKey: string } | null | undefined
let subscriptions: Map<string, StoredSubscription> | null = null

function settings() {
  const c = useRuntimeConfig()
  return {
    publicKey: String(c.vapidPublicKey || ''),
    privateKey: String(c.vapidPrivateKey || ''),
    subject: String(c.vapidSubject || ''),
    storePath: String(c.pushStorePath || '.data/push-subscriptions.json'),
    allowedHosts: String(c.pushAllowedHosts || '').split(',').map((h) => h.trim()).filter(Boolean),
  }
}

/** The VAPID public key when push is configured, else null (the app hides the option). */
export function pushConfig(): { publicKey: string } | null {
  if (configured !== undefined) return configured
  const s = settings()
  if (!s.publicKey || !s.privateKey || !s.subject) {
    configured = null
  } else {
    webpush.setVapidDetails(s.subject, s.publicKey, s.privateKey)
    configured = { publicKey: s.publicKey }
  }
  return configured
}

/** Accepts only push-service endpoints with well-formed keys. */
export function validSubscription(input: unknown): Omit<StoredSubscription, 'user' | 'createdAt'> | null {
  const sub = input as { endpoint?: unknown; keys?: { p256dh?: unknown; auth?: unknown } } | null
  if (typeof sub?.endpoint !== 'string' || sub.endpoint.length > 1024) return null
  const p256dh = sub.keys?.p256dh
  const auth = sub.keys?.auth
  if (typeof p256dh !== 'string' || !/^[A-Za-z0-9_-]{80,100}$/.test(p256dh)) return null
  if (typeof auth !== 'string' || !/^[A-Za-z0-9_-]{16,32}$/.test(auth)) return null
  let url: URL
  try {
    url = new URL(sub.endpoint)
  } catch {
    return null
  }
  if (url.protocol !== 'https:') return null
  const hosts = [...DEFAULT_PUSH_HOSTS, ...settings().allowedHosts]
  const allowed = hosts.some((h) => (h.startsWith('.') ? url.hostname.endsWith(h) : url.hostname === h))
  return allowed ? { endpoint: sub.endpoint, keys: { p256dh, auth } } : null
}

async function load(): Promise<Map<string, StoredSubscription>> {
  if (subscriptions) return subscriptions
  try {
    const list = JSON.parse(await readFile(settings().storePath, 'utf8')) as StoredSubscription[]
    subscriptions = new Map(list.map((s) => [s.endpoint, s]))
  } catch {
    subscriptions = new Map()
  }
  return subscriptions
}

async function save() {
  const path = settings().storePath
  await mkdir(dirname(path), { recursive: true })
  // Write then rename, so a crash never leaves a half-written file.
  await writeFile(`${path}.tmp`, JSON.stringify([...(await load()).values()]), { mode: 0o600 })
  await rename(`${path}.tmp`, path)
}

export async function addSubscription(sub: Omit<StoredSubscription, 'user' | 'createdAt'>, user: string | null) {
  const subs = await load()
  if (!subs.has(sub.endpoint) && subs.size >= 500) throw new Error('too many subscriptions')
  subs.set(sub.endpoint, { ...sub, user, createdAt: Date.now() })
  await save()
}

export async function removeSubscription(endpoint: string) {
  const subs = await load()
  if (subs.delete(endpoint)) await save()
}

export async function subscriptionCount() {
  return (await load()).size
}

/**
 * Sends to every subscribed device. Subscriptions the push service reports as gone (404, 410:
 * the app was uninstalled or notifications turned off) are dropped.
 */
export async function pushToAll(payload: PushPayload): Promise<{ sent: number; failed: number; removed: number }> {
  if (!pushConfig()) return { sent: 0, failed: 0, removed: 0 }
  const subs = await load()
  let sent = 0
  let failed = 0
  const gone: string[] = []
  await Promise.all(
    [...subs.values()].map(async (s) => {
      try {
        await webpush.sendNotification({ endpoint: s.endpoint, keys: s.keys }, JSON.stringify(payload), {
          TTL: 3600,
          urgency: 'high',
          timeout: 10_000,
        })
        sent++
      } catch (err) {
        const status = (err as { statusCode?: number }).statusCode
        if (status === 404 || status === 410) gone.push(s.endpoint)
        else failed++
      }
    }),
  )
  if (gone.length) {
    gone.forEach((e) => subs.delete(e))
    await save()
  }
  return { sent, failed, removed: gone.length }
}
