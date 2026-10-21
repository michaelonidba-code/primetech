// Google sends the browser back here after consent. The session cookie is SameSite=Strict, so it is
// not sent on this cross-site return; the single-use state issued to a signed-in user stands in for it.
export default defineEventHandler(async (event) => {
  const q = getQuery(event)
  const back = (result: string) => sendRedirect(event, `/?google=${encodeURIComponent(result)}#youtube`, 302)
  const pending = typeof q.state === 'string' ? takeOAuthState(q.state) : null
  if (!pending) return back('expired')
  if (typeof q.error === 'string') return back(q.error === 'access_denied' ? 'denied' : 'failed')
  if (typeof q.code !== 'string' || !q.code) return back('failed')
  try {
    await exchangeOAuthCode(q.code, pending.redirectUri)
  } catch (err) {
    console.error('[google] OAuth exchange failed:', err instanceof Error ? err.message : err)
    return back('failed')
  }
  return back('connected')
})
