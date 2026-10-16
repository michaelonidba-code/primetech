// Reports the authentication mode once at startup, so a misconfiguration is visible in the logs.
export default defineNitroPlugin(() => {
  const auth = authState()
  if (auth.disabled) console.warn('[auth] DISABLED: the API is open to anyone who can reach this server')
  else if (auth.problem) console.error(`[auth] misconfigured, API answers 503: ${auth.problem}`)
  else console.info(`[auth] enabled for ${auth.users.size} user(s), sessions last ${auth.ttlMs / 3_600_000} h`)
})
