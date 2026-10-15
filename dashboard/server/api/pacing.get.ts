// Budget pacing state per line item, proxied from the pacing service.
export default defineEventHandler(async () => {
  const url = useRuntimeConfig().pacingUrl.replace(/\/+$/, '')
  try {
    const stats = await $fetch<Record<string, unknown>>(`${url}/stats`, { timeout: 2000 })
    return { available: true, ...stats }
  } catch (err) {
    return { available: false, error: err instanceof Error ? err.message : String(err) }
  }
})
