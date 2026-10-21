// Starts (ENABLED) or pauses a campaign. Starting is what makes Google begin spending.
export default defineEventHandler(async (event) => {
  requireSameOrigin(event)
  const body = await readJsonBody<{ campaignId?: unknown; status?: unknown } | null>(event)
  const id = typeof body?.campaignId === 'string' && /^\d{1,20}$/.test(body.campaignId) ? body.campaignId : ''
  const status = body?.status === 'ENABLED' || body?.status === 'PAUSED' ? body.status : null
  if (!id || !status) throw createError({ statusCode: 400, statusMessage: 'Expected a campaign ID and ENABLED or PAUSED' })
  return setCampaignStatus(id, status, (event.context.user as string | undefined) ?? 'local')
})
