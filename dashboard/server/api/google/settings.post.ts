// Saves Google settings typed into the console. Empty fields are left as they were; a field set to
// null is cleared. The refresh token is never set here, only by the OAuth callback.
export default defineEventHandler(async (event) => {
  requireSameOrigin(event)
  const body = await readJsonBody<Record<string, unknown> | null>(event)
  if (!body || typeof body !== 'object') throw createError({ statusCode: 400, statusMessage: 'Expected an object' })
  const patch: Partial<GoogleSettings> = {}
  for (const field of EDITABLE_FIELDS) {
    const v = body[field]
    if (v === null) patch[field] = ''
    else if (typeof v === 'string' && v.trim()) {
      const value = v.trim()
      if (value.length > 500) throw createError({ statusCode: 400, statusMessage: `${field} is too long` })
      patch[field] = value
    }
  }
  if (patch.channelHandle) {
    const handle = normalizeHandle(patch.channelHandle)
    if (!handle) throw createError({ statusCode: 400, statusMessage: 'Channel handle should look like @yourchannel' })
    patch.channelHandle = handle
  }
  for (const id of ['adsCustomerId', 'adsLoginCustomerId'] as const) {
    if (patch[id] && cleanCustomerId(patch[id]).length !== 10) {
      throw createError({ statusCode: 400, statusMessage: 'Google Ads customer IDs have 10 digits, like 123-456-7890' })
    }
  }
  if (patch.oauthClientId && !patch.oauthClientId.endsWith('.apps.googleusercontent.com')) {
    throw createError({ statusCode: 400, statusMessage: 'The OAuth client ID should end in .apps.googleusercontent.com' })
  }
  await writeGoogleSettings(patch)
  return googleStatus(event)
})
