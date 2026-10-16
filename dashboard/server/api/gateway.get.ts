// Ingest gateway counters, proxied so the browser only ever talks to this origin.
export default defineEventHandler(async () => {
  const url = useRuntimeConfig().gatewayUrl.replace(/\/+$/, '')
  try {
    return await $fetch<Record<string, unknown>>(`${url}/stats`, { timeout: 1500 })
  } catch {
    throw createError({ statusCode: 502, statusMessage: 'Gateway unreachable' })
  }
})
