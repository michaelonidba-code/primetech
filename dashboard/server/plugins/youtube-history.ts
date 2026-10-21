// Records the channel's subscribers, views and video count every hour, so the console can chart
// growth over time. Does nothing until a YouTube API key is saved.
export default defineNitroPlugin(() => {
  const HOUR = 3_600_000
  const snapshot = async () => {
    try {
      const s = await readGoogleSettings()
      if (!s.youtubeApiKey) return
      await appendHistory(await channelOverview(true))
    } catch (err) {
      console.error('[youtube-history]', err instanceof Error ? err.message : err)
    }
  }
  setTimeout(snapshot, 60_000).unref?.()
  setInterval(snapshot, HOUR).unref?.()
})
