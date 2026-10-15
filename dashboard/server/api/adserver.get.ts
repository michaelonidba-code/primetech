// Ad server counters (auctions, fill, latency, per-DSP outcomes), proxied so the browser needs no
// direct access to the ad server.
export default defineEventHandler(async () => {
  const url = useRuntimeConfig().adServerUrl.replace(/\/+$/, '')
  try {
    const stats = await $fetch<Record<string, unknown>>(`${url}/stats`, { timeout: 2000 })
    return { available: true, ...stats }
  } catch (err) {
    return { available: false, error: err instanceof Error ? err.message : String(err) }
  }
})
