// Campaign delivery from Druid. Billable numbers exclude invalid traffic (ivt = 1).
interface Row {
  advertiserName: string | null
  campaignName: string | null
  campaignId: string
  impressions: number | null
  completes: number | null
  spendMicros: number | null
  ivtEvents: number | null
  events: number
}

export default defineEventHandler(async (event) => {
  const druid = useRuntimeConfig().druidUrl.replace(/\/+$/, '')
  // Whole minutes only, clamped, so the value is safe to inline into SQL.
  const minutes = Math.min(1440, Math.max(5, Math.trunc(Number(getQuery(event).minutes) || 60)))
  const query = `
    SELECT advertiserName, campaignName, campaignId,
      SUM(events) FILTER (WHERE type = 'IMPRESSION' AND ivt = 0) AS impressions,
      SUM(events) FILTER (WHERE type = 'COMPLETE' AND ivt = 0) AS completes,
      SUM(priceMicros) FILTER (WHERE type = 'IMPRESSION' AND ivt = 0) AS spendMicros,
      SUM(events) FILTER (WHERE ivt = 1) AS ivtEvents,
      SUM(events) AS events
    FROM ad_events
    WHERE __time >= CURRENT_TIMESTAMP - INTERVAL '${minutes}' MINUTE
    GROUP BY 1, 2, 3
    ORDER BY spendMicros DESC
    LIMIT 15`
  try {
    const rows = await $fetch<Row[]>(`${druid}/druid/v2/sql`, {
      method: 'POST',
      body: { query },
      timeout: 4000,
    })
    return {
      available: true,
      minutes,
      rows: rows.map((r) => ({
        advertiserName: r.advertiserName ?? 'Unknown advertiser',
        campaignName: r.campaignName ?? r.campaignId,
        campaignId: r.campaignId,
        impressions: r.impressions ?? 0,
        completes: r.completes ?? 0,
        spendMicros: r.spendMicros ?? 0,
        ivtEvents: r.ivtEvents ?? 0,
        events: r.events,
      })),
    }
  } catch (err) {
    return { available: false, minutes, error: err instanceof Error ? err.message : String(err) }
  }
})
