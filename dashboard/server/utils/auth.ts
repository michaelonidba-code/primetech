// Password hashing, signed session tokens and login rate limiting for the hosted dashboard API.
// Self-contained (no Nuxt auto-imports) so it can be unit-tested with `node --test`.
import { createHash, createHmac, randomBytes, scryptSync, timingSafeEqual } from 'node:crypto'

export const SESSION_COOKIE = 'ae_session'

// scrypt cost: ~50 ms per verification on a laptop core, and login is rate limited.
const SCRYPT_N = 32768
const SCRYPT_R = 8
const SCRYPT_P = 1
const SCRYPT_MAXMEM = 64 * 1024 * 1024
const USERNAME = /^[A-Za-z0-9._-]{1,64}$/

const b64url = (buf: Buffer) => buf.toString('base64url')

/**
 * Hashes a password as `scrypt.N.r.p.salt.hash`. The format avoids `$`, `:` and `,` so it can
 * sit in an environment variable or a Compose .env file without quoting or interpolation.
 */
export function hashPassword(password: string, salt: Buffer = randomBytes(16)): string {
  const hash = scryptSync(password, salt, 32, { N: SCRYPT_N, r: SCRYPT_R, p: SCRYPT_P, maxmem: SCRYPT_MAXMEM })
  return ['scrypt', SCRYPT_N, SCRYPT_R, SCRYPT_P, b64url(salt), b64url(hash)].join('.')
}

export function verifyPassword(password: string, encoded: string): boolean {
  const parts = encoded.split('.')
  if (parts.length !== 6 || parts[0] !== 'scrypt') return false
  const [n, r, p] = parts.slice(1, 4).map(Number)
  if (!n || !r || !p || n > 1 << 20) return false
  const expected = Buffer.from(parts[5]!, 'base64url')
  if (expected.length !== 32) return false
  try {
    const actual = scryptSync(password, Buffer.from(parts[4]!, 'base64url'), 32, { N: n, r, p, maxmem: SCRYPT_MAXMEM })
    return timingSafeEqual(actual, expected)
  } catch {
    return false // invalid scrypt parameters in a configured hash
  }
}

/** Parses `alice=scrypt...,bob=scrypt...`. Malformed entries are reported, not silently dropped. */
export function parseUsers(spec: string): { users: Map<string, string>; errors: string[] } {
  const users = new Map<string, string>()
  const errors: string[] = []
  for (const entry of spec.split(',').map((e) => e.trim()).filter(Boolean)) {
    const eq = entry.indexOf('=')
    const name = eq > 0 ? entry.slice(0, eq) : ''
    const hash = eq > 0 ? entry.slice(eq + 1) : ''
    if (!USERNAME.test(name) || !hash.startsWith('scrypt.') || hash.split('.').length !== 6) {
      errors.push(`malformed user entry #${users.size + errors.length + 1}`)
    } else {
      users.set(name, hash)
    }
  }
  return { users, errors }
}

interface SessionPayload {
  u: string
  exp: number
  /** Fingerprint of the user's password hash: changing the password ends existing sessions. */
  v: string
}

const fingerprint = (passwordHash: string) => b64url(createHash('sha256').update(passwordHash).digest()).slice(0, 12)
const sign = (data: string, secret: string) => b64url(createHmac('sha256', secret).update(data).digest())

export function createSession(user: string, passwordHash: string, secret: string, ttlMs: number, now = Date.now()): string {
  const payload: SessionPayload = { u: user, exp: now + ttlMs, v: fingerprint(passwordHash) }
  const body = b64url(Buffer.from(JSON.stringify(payload)))
  return `${body}.${sign(body, secret)}`
}

/** Returns the user name for a valid, unexpired session whose password has not changed since. */
export function verifySession(
  token: string | undefined,
  users: Map<string, string>,
  secret: string,
  now = Date.now(),
): string | null {
  if (!token) return null
  const dot = token.indexOf('.')
  if (dot <= 0) return null
  const body = token.slice(0, dot)
  const mac = Buffer.from(token.slice(dot + 1), 'base64url')
  const expected = Buffer.from(sign(body, secret), 'base64url')
  if (mac.length !== expected.length || !timingSafeEqual(mac, expected)) return null
  let payload: SessionPayload
  try {
    payload = JSON.parse(Buffer.from(body, 'base64url').toString('utf8'))
  } catch {
    return null
  }
  const hash = typeof payload.u === 'string' ? users.get(payload.u) : undefined
  if (!hash || typeof payload.exp !== 'number' || payload.exp <= now || payload.v !== fingerprint(hash)) return null
  return payload.u
}

/** Fixed-window counter per key, e.g. failed logins per client address and per user name. */
export class RateLimiter {
  private readonly windows = new Map<string, { count: number; resetAt: number }>()
  private readonly limit: number
  private readonly windowMs: number

  constructor(limit: number, windowMs: number) {
    this.limit = limit
    this.windowMs = windowMs
  }

  /** True while the key is under its limit. */
  allowed(key: string, now = Date.now()): boolean {
    const w = this.windows.get(key)
    return !w || w.resetAt <= now || w.count < this.limit
  }

  record(key: string, now = Date.now()): void {
    if (this.windows.size > 10_000) this.prune(now)
    const w = this.windows.get(key)
    if (!w || w.resetAt <= now) this.windows.set(key, { count: 1, resetAt: now + this.windowMs })
    else w.count++
  }

  reset(key: string): void {
    this.windows.delete(key)
  }

  private prune(now: number) {
    for (const [k, w] of this.windows) if (w.resetAt <= now) this.windows.delete(k)
  }
}
