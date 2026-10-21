// Deletes a sponsor campaign and its video file.
export default defineEventHandler(async (event) => {
  requireSameOrigin(event)
  const body = await readJsonBody<{ id?: unknown } | null>(event)
  if (typeof body?.id !== 'string') throw createError({ statusCode: 400, statusMessage: 'Expected an id' })
  await deleteSponsor(body.id, (event.context.user as string | undefined) ?? 'local')
  return { deleted: true }
})
