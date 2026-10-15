// Every data route is read-only and requires a signed session cookie. The only writes are signing in
// and out, (un)subscribing this device to notifications, and Alertmanager's webhook.
// The alert webhook authenticates with its own bearer token (Alertmanager has no session).
const OPEN = new Set(['/api/health', '/api/auth/login', '/api/auth/logout', '/api/alerts/webhook'])
const WRITES = new Set(['/api/auth/login', '/api/auth/logout', '/api/alerts/webhook', '/api/push/subscribe', '/api/push/unsubscribe'])

export default defineEventHandler((event) => {
  setResponseHeaders(event, {
    'X-Content-Type-Options': 'nosniff',
    'Referrer-Policy': 'no-referrer',
    'X-Frame-Options': 'DENY',
    'Content-Security-Policy': "frame-ancestors 'none'",
  })
  const path = getRequestURL(event).pathname
  if (!path.startsWith('/api/')) return
  setResponseHeader(event, 'Cache-Control', 'no-store')

  const method = event.method
  if (WRITES.has(path) ? method !== 'POST' : method !== 'GET' && method !== 'HEAD') {
    throw createError({ statusCode: 405, statusMessage: 'Method Not Allowed' })
  }

  const auth = authState()
  if (auth.disabled || path === '/api/health') return
  if (auth.problem) throw createError({ statusCode: 503, statusMessage: 'Authentication is not configured' })
  if (OPEN.has(path)) return

  const user = verifySession(getCookie(event, SESSION_COOKIE), auth.users, auth.secret)
  if (!user) throw createError({ statusCode: 401, statusMessage: 'Sign in required' })
  event.context.user = user
})
