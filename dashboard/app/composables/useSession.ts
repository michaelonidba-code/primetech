export type SessionStatus =
  | 'checking'
  | 'signed-in' // authenticated
  | 'open' // the server runs with authentication disabled
  | 'signed-out' // the server wants a login
  | 'misconfigured' // authentication is on but the server cannot check logins
  | 'no-server' // static build (GitHub Pages) or server unreachable: panels explain themselves

interface Me {
  user: string | null
  authDisabled: boolean
}

function statusOf(err: unknown): number | undefined {
  return (err as { statusCode?: number; response?: { status?: number } })?.statusCode
    ?? (err as { response?: { status?: number } })?.response?.status
}

/** Shared sign-in state. Any API call that answers 401 sends the app back to the sign-in screen. */
export function useSession() {
  const status = useState<SessionStatus>('session-status', () => 'checking')
  const user = useState<string | null>('session-user', () => null)

  async function check() {
    try {
      const me = await $fetch<Me>('/api/auth/me', { timeout: 5000 })
      user.value = me.user
      status.value = me.authDisabled ? 'open' : 'signed-in'
    } catch (err) {
      const code = statusOf(err)
      status.value = code === 401 ? 'signed-out' : code === 503 ? 'misconfigured' : 'no-server'
    }
  }

  async function signIn(username: string, password: string) {
    const me = await $fetch<Me>('/api/auth/login', { method: 'POST', body: { username, password } })
    user.value = me.user
    status.value = me.authDisabled ? 'open' : 'signed-in'
  }

  async function signOut() {
    await $fetch('/api/auth/logout', { method: 'POST' }).catch(() => {})
    user.value = null
    status.value = 'signed-out'
  }

  /** Called by pollers on any failed request. */
  function noteError(err: unknown) {
    if (statusOf(err) === 401 && status.value === 'signed-in') {
      user.value = null
      status.value = 'signed-out'
    }
  }

  return { status, user, check, signIn, signOut, noteError, statusOf }
}
