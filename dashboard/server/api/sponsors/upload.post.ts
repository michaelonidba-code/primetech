// Uploads the campaign's MP4 (raw body, Content-Type: video/mp4). A new video needs approving again.
export default defineEventHandler(async (event) => {
  requireSameOrigin(event)
  const id = getQuery(event).id
  if (typeof id !== 'string' || !/^[0-9a-f]{10}$/.test(id)) throw createError({ statusCode: 400, statusMessage: 'Missing campaign id' })
  return uploadCreative(event, id, (event.context.user as string | undefined) ?? 'local')
})
