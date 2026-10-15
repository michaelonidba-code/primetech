// Registers this device for alert notifications. Signed-in users only (the middleware checks).
export default defineEventHandler(async (event) => {
  if (!pushConfig()) throw createError({ statusCode: 503, statusMessage: 'Push notifications are not configured' })
  if (!getRequestHeader(event, 'content-type')?.startsWith('application/json')) {
    throw createError({ statusCode: 415, statusMessage: 'Expected application/json' })
  }
  const sub = validSubscription(await readBody(event).catch(() => null))
  if (!sub) throw createError({ statusCode: 400, statusMessage: 'Not a push subscription from a supported browser' })
  await addSubscription(sub, (event.context.user as string | undefined) ?? null)
  return { subscribed: true }
})
