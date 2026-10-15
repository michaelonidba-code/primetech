// Reconciliation results, proxied from the audit service.
export default defineEventHandler(async () => {
  const url = useRuntimeConfig().auditUrl.replace(/\/+$/, '')
  try {
    return { available: true, ...(await $fetch<Record<string, unknown>>(`${url}/stats`, { timeout: 3000 })) }
  } catch (err) {
    return { available: false, error: err instanceof Error ? err.message : String(err) }
  }
})
