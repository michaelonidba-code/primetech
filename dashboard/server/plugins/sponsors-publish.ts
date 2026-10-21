// Publishes the sponsor catalog at start-up and every 15 minutes, so campaigns start and stop on
// their dates without anyone touching them.
export default defineNitroPlugin(() => {
  const run = () => publishSponsors().catch((err) => console.error('[sponsors]', err instanceof Error ? err.message : err))
  setTimeout(run, 5_000).unref?.()
  setInterval(run, 15 * 60_000).unref?.()
})
