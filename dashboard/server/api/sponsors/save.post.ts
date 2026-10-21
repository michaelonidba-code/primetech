// Creates a sponsor campaign (as a draft) or edits one. Material edits send it back for approval.
export default defineEventHandler(async (event) => {
  requireSameOrigin(event)
  return saveCampaign(await readJsonBody(event), (event.context.user as string | undefined) ?? 'local')
})
