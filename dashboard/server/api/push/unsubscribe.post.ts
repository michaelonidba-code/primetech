export default defineEventHandler(async (event) => {
  if (!getRequestHeader(event, 'content-type')?.startsWith('application/json')) {
    throw createError({ statusCode: 415, statusMessage: 'Expected application/json' })
  }
  const body = await readBody<{ endpoint?: unknown }>(event).catch(() => null)
  if (typeof body?.endpoint === 'string') await removeSubscription(body.endpoint)
  return { subscribed: false }
})
