// Exchanges a user name and password for a session cookie (HttpOnly, SameSite=Strict, Secure on HTTPS).
// Failed attempts are limited per client address and per user name; unknown users cost the same
// time as known ones, so the response does not reveal which names exist.
const byAddress = new RateLimiter(20, 15 * 60_000)
const byUser = new RateLimiter(10, 15 * 60_000)
let dummyHash: string | null = null

function originHost(origin: string): string | null {
  try {
    return new URL(origin).host
  } catch {
    return null // e.g. "Origin: null" from a sandboxed frame
  }
}

export default defineEventHandler(async (event) => {
  const origin = getRequestHeader(event, 'origin')
  if (origin && originHost(origin) !== getRequestHost(event, { xForwardedHost: authState().trustProxy })) {
    throw createError({ statusCode: 403, statusMessage: 'Cross-origin login refused' })
  }
  // JSON only: a cross-site HTML form cannot send it without a CORS preflight, which is refused.
  if (!getRequestHeader(event, 'content-type')?.startsWith('application/json')) {
    throw createError({ statusCode: 415, statusMessage: 'Expected application/json' })
  }
  if (Number(getRequestHeader(event, 'content-length') || 0) > 4096) {
    throw createError({ statusCode: 413, statusMessage: 'Request too large' })
  }

  const auth = authState()
  if (auth.disabled) return { user: null, authDisabled: true }

  const body = await readBody<{ username?: unknown; password?: unknown }>(event).catch(() => null)
  const username = typeof body?.username === 'string' ? body.username.slice(0, 64) : ''
  const password = typeof body?.password === 'string' ? body.password.slice(0, 1024) : ''
  const address = clientAddress(event)
  if (!byAddress.allowed(address) || !byUser.allowed(username)) {
    setResponseHeader(event, 'Retry-After', 900)
    throw createError({ statusCode: 429, statusMessage: 'Too many failed attempts, try again later' })
  }

  const hash = auth.users.get(username)
  dummyHash ??= hashPassword('not-a-real-password')
  const ok = verifyPassword(password, hash ?? dummyHash) && hash !== undefined
  if (!ok) {
    byAddress.record(address)
    byUser.record(username)
    console.warn(`[auth] failed login from ${address}`)
    throw createError({ statusCode: 401, statusMessage: 'Wrong user name or password' })
  }

  byUser.reset(username)
  setCookie(event, SESSION_COOKIE, createSession(username, hash, auth.secret, auth.ttlMs), {
    httpOnly: true,
    sameSite: 'strict',
    secure: isHttps(event),
    path: '/',
    maxAge: Math.floor(auth.ttlMs / 1000),
  })
  return { user: username, authDisabled: false }
})
