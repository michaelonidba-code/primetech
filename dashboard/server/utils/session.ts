// Auth configuration from runtime config, resolved once. Fails closed: if authentication is on but
// misconfigured, every protected API call answers 503 rather than serving data unauthenticated.
import type { H3Event } from 'h3'

export interface AuthState {
  disabled: boolean
  users: Map<string, string>
  secret: string
  ttlMs: number
  trustProxy: boolean
  /** Why authentication cannot work, if it cannot. Never contains secrets. */
  problem: string | null
}

let cached: AuthState | null = null

export function authState(): AuthState {
  if (cached) return cached
  const config = useRuntimeConfig()
  const disabled = String(config.authDisabled) === 'true'
  const { users, errors } = parseUsers(String(config.authUsers || ''))
  const secret = String(config.sessionSecret || '')
  let problem: string | null = null
  if (!disabled) {
    if (errors.length) problem = `NUXT_AUTH_USERS has ${errors.length} malformed entr${errors.length === 1 ? 'y' : 'ies'}`
    else if (users.size === 0) problem = 'NUXT_AUTH_USERS is empty'
    else if (secret.length < 32) problem = 'NUXT_SESSION_SECRET must be at least 32 characters'
  }
  cached = {
    disabled,
    users,
    secret,
    ttlMs: Math.max(1, Number(config.sessionTtlHours) || 12) * 3_600_000,
    trustProxy: String(config.trustProxy) === 'true',
    problem,
  }
  return cached
}

/** The client address, from X-Forwarded-For only when a trusted proxy (Caddy) sets it. */
export function clientAddress(event: H3Event): string {
  return getRequestIP(event, { xForwardedFor: authState().trustProxy }) ?? 'unknown'
}

export function isHttps(event: H3Event): boolean {
  return getRequestProtocol(event, { xForwardedProto: authState().trustProxy }) === 'https'
}
