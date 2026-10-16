// Stream-processor health from the Flink REST API. Runs server-side, so the browser needs no
// access (or CORS) to Flink.
interface JobOverview { jobs: { jid: string; name: string; state: string; 'start-time': number; duration: number }[] }
interface JobDetail { vertices: { id: string; name: string }[] }
interface Checkpoints {
  counts: { completed: number; failed: number; restored: number }
  latest: { completed: { id: number; latest_ack_timestamp: number; end_to_end_duration: number; state_size: number } | null }
}
type MetricValues = { id: string; sum: number }[]

export default defineEventHandler(async () => {
  const flink = useRuntimeConfig().flinkUrl.replace(/\/+$/, '')
  try {
    const overview = await $fetch<JobOverview>(`${flink}/jobs/overview`, { timeout: 2000 })
    const job = overview.jobs.find((j) => j.state === 'RUNNING') ?? overview.jobs[0]
    if (!job) return { available: true, job: null }

    const [detail, checkpoints] = await Promise.all([
      $fetch<JobDetail>(`${flink}/jobs/${job.jid}`, { timeout: 2000 }),
      $fetch<Checkpoints>(`${flink}/jobs/${job.jid}/checkpoints`, { timeout: 2000 }),
    ])

    // Operator metrics are named "<operator name>.<metric>" and summed across parallel subtasks.
    async function metric(operator: string, name: string): Promise<number | null> {
      const vertex = detail.vertices.find((v) => v.name.includes(operator))
      if (!vertex) return null
      const values = await $fetch<MetricValues>(
        `${flink}/jobs/${job!.jid}/vertices/${vertex.id}/subtasks/metrics`,
        { query: { get: `${operator}.${name}`, agg: 'sum' }, timeout: 2000 },
      )
      return values[0]?.sum ?? null
    }

    const [cleanPerSecond, duplicatesDropped, ivtFlagged, metadataMissing, lateDropped] = await Promise.all([
      metric('restore-event-time', 'numRecordsOutPerSecond'),
      metric('dedup-by-event-id', 'duplicatesDropped'),
      metric('flag-invalid-traffic', 'ivtFlagged'),
      metric('enrich-with-campaign', 'metadataMissing'),
      metric('line-item-minute-totals', 'numLateRecordsDropped'),
    ])

    const latest = checkpoints.latest.completed
    return {
      available: true,
      job: { name: job.name, state: job.state, uptimeMs: job.duration },
      checkpoints: {
        completed: checkpoints.counts.completed,
        failed: checkpoints.counts.failed,
        restored: checkpoints.counts.restored,
        lastCompletedAt: latest?.latest_ack_timestamp ?? null,
        lastDurationMs: latest?.end_to_end_duration ?? null,
        lastStateBytes: latest?.state_size ?? null,
      },
      metrics: { cleanPerSecond, duplicatesDropped, ivtFlagged, metadataMissing, lateDropped },
    }
  } catch (err) {
    return { available: false, error: err instanceof Error ? err.message : String(err) }
  }
})
