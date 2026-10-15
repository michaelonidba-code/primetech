function base64UrlToBytes(value: string): Uint8Array<ArrayBuffer> {
  const padded = value.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (value.length % 4)) % 4)
  const raw = atob(padded)
  const bytes = new Uint8Array(new ArrayBuffer(raw.length))
  for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i)
  return bytes
}

export type PushState =
  | 'checking'
  | 'unsupported' // this browser cannot receive push (e.g. iOS Safari outside a home-screen app)
  | 'unavailable' // the server has no VAPID keys
  | 'blocked' // the user denied notifications for this site
  | 'off'
  | 'on'

/** Alert notifications on this device: Web Push through the service worker. */
export function usePush() {
  const state = ref<PushState>('checking')
  const busy = ref(false)
  const error = ref('')
  let publicKey: string | null = null

  async function registration() {
    return navigator.serviceWorker.ready
  }

  async function refresh() {
    if (!('serviceWorker' in navigator) || !('PushManager' in window) || !('Notification' in window)) {
      state.value = 'unsupported'
      return
    }
    try {
      const key = await $fetch<{ enabled: boolean; publicKey: string | null }>('/api/push/key', { timeout: 5000 })
      publicKey = key.publicKey
      if (!key.enabled) {
        state.value = 'unavailable'
        return
      }
    } catch {
      state.value = 'unavailable'
      return
    }
    if (Notification.permission === 'denied') {
      state.value = 'blocked'
      return
    }
    const sub = await (await registration()).pushManager.getSubscription()
    state.value = sub ? 'on' : 'off'
  }

  async function enable() {
    if (!publicKey) return
    busy.value = true
    error.value = ''
    try {
      if ((await Notification.requestPermission()) !== 'granted') {
        state.value = Notification.permission === 'denied' ? 'blocked' : 'off'
        return
      }
      const reg = await registration()
      // The browser registers with its vendor's push service first; if that is unreachable the
      // promise can hang for minutes. Say so instead of waiting silently.
      const sub = await Promise.race([
        reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: base64UrlToBytes(publicKey) }),
        new Promise<never>((_, reject) =>
          setTimeout(() => reject(new Error("Couldn't reach the browser's push service. Try again later.")), 20_000)),
      ])
      await $fetch('/api/push/subscribe', { method: 'POST', body: sub.toJSON() })
      state.value = 'on'
    } catch (err) {
      error.value = err instanceof Error ? err.message : String(err)
    } finally {
      busy.value = false
    }
  }

  async function disable() {
    busy.value = true
    try {
      const sub = await (await registration()).pushManager.getSubscription()
      if (sub) {
        await $fetch('/api/push/unsubscribe', { method: 'POST', body: { endpoint: sub.endpoint } }).catch(() => {})
        await sub.unsubscribe()
      }
      state.value = 'off'
    } finally {
      busy.value = false
    }
  }

  onMounted(refresh)
  return { state, busy, error, enable, disable }
}
