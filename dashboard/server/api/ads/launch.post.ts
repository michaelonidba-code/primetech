// Creates a paused, YouTube-only Demand Gen campaign for one video. Nothing is spent until Start.
export default defineEventHandler(async (event) => {
  requireSameOrigin(event)
  const { value, errors } = validateLaunch(await readJsonBody(event), maxDailyBudget())
  if (!value) throw createError({ statusCode: 400, statusMessage: errors.join(' ') })
  return launchDemandGen(value, (event.context.user as string | undefined) ?? 'local')
})
