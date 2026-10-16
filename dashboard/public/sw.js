// Service worker: makes the dashboard installable and lets the app shell open offline.
//
// What is cached, and how:
//   pages (navigations)         network first, cached copy when offline
//   /_nuxt/* build assets       cache first: file names are content-hashed, so never stale
//   icons, logo, manifest       served from cache, refreshed in the background
//   /api/*                      NEVER cached: live, signed-in data must not outlive the session
//                               or be left on a shared device
const VERSION = 'ae-v3'
const SHELL = `${VERSION}-shell`
const ASSETS = `${VERSION}-assets`
const MAX_ASSETS = 80
const scope = new URL(self.registration.scope)
const PRECACHE = ['./', 'manifest.webmanifest', 'icons/icon-192.png', 'icons/icon-512.png', 'brand-logo.png'].map(
  (p) => new URL(p, scope).href,
)

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(SHELL).then((c) => c.addAll(PRECACHE)).then(() => self.skipWaiting()))
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => !k.startsWith(VERSION)).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  )
})

async function trim(cacheName, max) {
  const cache = await caches.open(cacheName)
  const keys = await cache.keys()
  await Promise.all(keys.slice(0, Math.max(0, keys.length - max)).map((k) => cache.delete(k)))
}

self.addEventListener('fetch', (event) => {
  const request = event.request
  const url = new URL(request.url)
  if (request.method !== 'GET' || url.origin !== scope.origin || url.pathname.includes('/api/')) return

  if (request.mode === 'navigate') {
    event.respondWith(
      fetch(request)
        .then((response) => {
          if (response.ok) {
            const copy = response.clone()
            caches.open(SHELL).then((c) => c.put(scope.href, copy))
          }
          return response
        })
        .catch(() => caches.match(scope.href, { cacheName: SHELL }).then((r) => r || Response.error())),
    )
    return
  }

  if (url.pathname.includes('/_nuxt/')) {
    event.respondWith(
      caches.match(request, { cacheName: ASSETS }).then(
        (cached) =>
          cached ||
          fetch(request).then((response) => {
            if (response.ok) {
              const copy = response.clone()
              caches.open(ASSETS).then((c) => c.put(request, copy)).then(() => trim(ASSETS, MAX_ASSETS))
            }
            return response
          }),
      ),
    )
    return
  }

  if (PRECACHE.includes(url.href)) {
    event.respondWith(
      caches.match(request, { cacheName: SHELL }).then((cached) => {
        const refresh = fetch(request).then((response) => {
          if (response.ok) caches.open(SHELL).then((c) => c.put(request, response.clone()))
          return response
        })
        return cached || refresh
      }),
    )
  }
})

// Alert notifications (Web Push). The server sends {title, body, tag, url}; a notification with the
// same tag replaces the previous one, so "Resolved" replaces its own "Critical" on the lock screen.
self.addEventListener('push', (event) => {
  let data = {}
  try {
    data = event.data ? event.data.json() : {}
  } catch {
    data = { title: 'Ads Eventing', body: event.data ? event.data.text() : '' }
  }
  event.waitUntil(
    self.registration.showNotification(data.title || 'Ads Eventing alert', {
      body: data.body || '',
      tag: data.tag || 'ads-eventing',
      renotify: true,
      icon: new URL('icons/icon-192.png', scope).href,
      badge: new URL('icons/icon-192.png', scope).href,
      data: { url: new URL((data.url || './').replace(/^\//, ''), scope).href },
    }),
  )
})

// Tapping a notification opens the app (or focuses it) at the alerts panel.
self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  const target = event.notification.data?.url || scope.href
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((windows) => {
      const open = windows.find((w) => w.url.startsWith(scope.href))
      if (open) return open.focus().then(() => open.navigate(target))
      return self.clients.openWindow(target)
    }),
  )
})
