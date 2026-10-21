// State for the YouTube growth console: Google connection status, channel statistics with history,
// and Google Ads campaigns. Shared across components (one instance per page).

export const GOALS = { subscribers: 1_000_000, views: 10_000_000 } as const

export interface GoogleStatus {
  settings: Record<string, string>
  channelHandle: string
  youtubeReady: boolean
  oauthReady: boolean
  connected: boolean
  connectedEmail: string
  adsReady: boolean
  redirectUri: string
  apiVersion: string
  maxDailyBudget: number
}

export interface Video {
  id: string
  title: string
  publishedAt: string
  thumbnail: string
  duration: string
  views: number
  likes: number
  comments: number
}

export interface Overview {
  fetchedAt: string
  channel: { id: string; title: string; handle: string; url: string; thumbnail: string; subscribers: number | null; views: number; videos: number }
  videos: Video[]
  history: { t: string; subscribers: number | null; views: number; videos: number }[]
}

export interface Campaign {
  id: string
  name: string
  status: string
  type: string
  cost: number
  dailyBudget: number | null
  impressions: number
  clicks: number
  videoViews: number | null
  engagements: number
}

export interface AdsData {
  account: { id: string; name: string; currency: string; timeZone: string }
  campaigns: Campaign[]
}

/** The readable message from a failed $fetch (Nitro puts it in statusMessage). */
export function errorText(err: unknown): string {
  const e = err as { data?: { statusMessage?: string; message?: string }; statusMessage?: string; message?: string }
  return e?.data?.statusMessage || e?.data?.message || e?.statusMessage || e?.message || 'Something went wrong'
}

export function useGrowth() {
  const session = useSession()
  const status = useState<GoogleStatus | null>('growth-status', () => null)
  const overview = useState<Overview | null>('growth-overview', () => null)
  const ads = useState<AdsData | null>('growth-ads', () => null)
  const errors = useState<{ status?: string; overview?: string; ads?: string }>('growth-errors', () => ({}))
  const loading = useState('growth-loading', () => false)

  async function loadStatus() {
    try {
      status.value = await $fetch<GoogleStatus>('/api/google/status', { timeout: 8000 })
      errors.value = { ...errors.value, status: undefined }
    } catch (err) {
      session.noteError(err)
      errors.value = { ...errors.value, status: errorText(err) }
    }
  }

  async function loadOverview(refresh = false) {
    if (!status.value?.youtubeReady) return
    try {
      overview.value = await $fetch<Overview>('/api/youtube/overview', { query: refresh ? { refresh: '1' } : {}, timeout: 20000 })
      errors.value = { ...errors.value, overview: undefined }
    } catch (err) {
      session.noteError(err)
      errors.value = { ...errors.value, overview: errorText(err) }
    }
  }

  async function loadAds() {
    if (!status.value?.adsReady) return
    try {
      ads.value = await $fetch<AdsData>('/api/ads/campaigns', { timeout: 30000 })
      errors.value = { ...errors.value, ads: undefined }
    } catch (err) {
      session.noteError(err)
      errors.value = { ...errors.value, ads: errorText(err) }
    }
  }

  async function load(refresh = false) {
    loading.value = true
    try {
      await loadStatus()
      await Promise.all([loadOverview(refresh), loadAds()])
    } finally {
      loading.value = false
    }
  }

  async function saveSettings(patch: Record<string, string | null>) {
    status.value = await $fetch<GoogleStatus>('/api/google/settings', { method: 'POST', body: patch })
    await Promise.all([loadOverview(true), loadAds()])
  }

  async function disconnect() {
    status.value = await $fetch<GoogleStatus>('/api/google/disconnect', { method: 'POST', body: {} })
    ads.value = null
  }

  async function launch(input: Record<string, unknown>) {
    const res = await $fetch<{ campaignId: string; status: string }>('/api/ads/launch', { method: 'POST', body: input, timeout: 60000 })
    await loadAds()
    return res
  }

  async function setCampaignStatus(campaignId: string, next: 'ENABLED' | 'PAUSED') {
    await $fetch('/api/ads/status', { method: 'POST', body: { campaignId, status: next } })
    await loadAds()
  }

  return { status, overview, ads, errors, loading, load, loadOverview, loadAds, saveSettings, disconnect, launch, setCampaignStatus }
}
