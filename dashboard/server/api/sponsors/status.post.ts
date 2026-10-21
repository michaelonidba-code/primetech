// Approves (starts) or pauses a sponsor campaign.
export default defineEventHandler(async (event) => {
  requireSameOrigin(event)
  const body = await readJsonBody<{ id?: unknown; status?: unknown } | null>(event)
  const id = typeof body?.id === 'string' ? body.id : ''
  const status = body?.status === 'approved' || body?.status === 'paused' ? body.status : null
  if (!id || !status) throw createError({ statusCode: 400, statusMessage: 'Expected an id and approved or paused' })
  return setSponsorStatus(id, status, (event.context.user as string | undefined) ?? 'local')
})
