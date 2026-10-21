// Server-side state for the YouTube growth console: the Google settings file (secrets never leave
// the server), OAuth tokens, YouTube Data API reads and Google Ads API calls.
import { randomBytes } from 'node:crypto'
import { appendFile, chmod, mkdir, readFile, rename, writeFile } from 'node:fs/promises'
import { dirname } from 'node:path'
import type { H3Event } from 'h3'
import { authState } from './session'
import {
  campaignRows,
  cleanCustomerId,
  demandGenOperations,
  fromMicros,
  googleErrorMessage,
  maskSecret,
  normalizeHandle,
  type LaunchInput,
} from './google-ads'

export interface GoogleSettings {
  youtubeApiKey?: string
  channelHandle?: string
  oauthClientId?: string
  oauthClientSecret?: string
  refreshToken?: string
  connectedEmail?: string
  adsDeveloperToken?: string
  adsCustomerId?: string
  adsLoginCustomerId?: string
  updatedAt?: string
}

/** Fields the settings form may set. The refresh token and email only come from the OAuth flow. */
export const EDITABLE_FIELDS = [
  'youtubeApiKey',
  'channelHandle',
  'oauthClientId',
  'oauthClientSecret',
  'adsDeveloperToken',
  'adsCustomerId',
  'adsLoginCustomerId',
] as const
export const SECRET_FIELDS = new Set(['youtubeApiKey', 'oauthClientSecret', 'adsDeveloperToken'])
export const DEFAULT_HANDLE = '@aeshamikaelsonTV'

/** fetch() that turns network failures (DNS, timeouts, refused) into a readable 502. */
async function googleFetch(url: string, init: RequestInit | undefined, what: string): Promise<Response> {
  try {
    return await fetch(url, { ...init, signal: AbortSignal.timeout(30_000) })
  } catch (err) {
    const reason = err instanceof Error ? (err.cause instanceof Error ? err.cause.message : err.message) : String(err)
    throw createError({ statusCode: 502, statusMessage: `Could not reach ${what}: ${reason}` })
  }
}

function cfg() {
  const c = useRuntimeConfig()
  return {
    storePath: c.googleStorePath as string,
    historyPath: c.youtubeHistoryPath as string,
    actionsPath: c.adsActionsLogPath as string,
    apiVersion: c.googleAdsApiVersion as string,
    maxDailyBudget: Number(c.adsMaxDailyBudget) || 1000,
  }
}

let settingsCache: GoogleSettings | null = null

export async function readGoogleSettings(): Promise<GoogleSettings> {
  if (settingsCache) return settingsCache
  try {
    settingsCache = JSON.parse(await readFile(cfg().storePath, 'utf8')) as GoogleSettings
  } catch {
    settingsCache = {}
  }
  return settingsCache
}

export async function writeGoogleSettings(patch: Partial<GoogleSettings>): Promise<GoogleSettings> {
  const next: GoogleSettings = { ...(await readGoogleSettings()), ...patch, updatedAt: new Date().toISOString() }
  for (const [k, v] of Object.entries(next)) if (v === '' || v === undefined) delete (next as Record<string, unknown>)[k]
  const path = cfg().storePath
  await mkdir(dirname(path), { recursive: true })
  const tmp = `${path}.${process.pid}.tmp`
  await writeFile(tmp, JSON.stringify(next, null, 2), { mode: 0o600 })
  await rename(tmp, path)
  await chmod(path, 0o600).catch(() => {})
  settingsCache = next
  accessTokenCache = null
  overviewCache = null
  return next
}

/** What the browser may see: secrets masked, plus what is ready to use. */
export async function googleStatus(event: H3Event) {
  const s = await readGoogleSettings()
  const masked: Record<string, string> = {}
  for (const f of EDITABLE_FIELDS) {
    const v = s[f]
    masked[f] = !v ? '' : SECRET_FIELDS.has(f) ? maskSecret(v) : v
  }
  return {
    settings: masked,
    channelHandle: normalizeHandle(s.channelHandle) || DEFAULT_HANDLE,
    youtubeReady: Boolean(s.youtubeApiKey),
    oauthReady: Boolean(s.oauthClientId && s.oauthClientSecret),
    connected: Boolean(s.refreshToken),
    connectedEmail: s.connectedEmail ?? '',
    adsReady: Boolean(s.refreshToken && s.adsDeveloperToken && cleanCustomerId(s.adsCustomerId)),
    redirectUri: oauthRedirectUri(event),
    apiVersion: cfg().apiVersion,
    maxDailyBudget: cfg().maxDailyBudget,
  }
}

// ---- Requests from the browser ---------------------------------------------------------------

/** Refuses writes whose Origin is another site (belt and braces on top of SameSite=Strict). */
export function requireSameOrigin(event: H3Event) {
  const origin = getRequestHeader(event, 'origin')
  if (!origin) return
  let host: string | null = null
  try {
    host = new URL(origin).host
  } catch {
    host = null
  }
  if (host !== getRequestHost(event, { xForwardedHost: authState().trustProxy })) {
    throw createError({ statusCode: 403, statusMessage: 'Cross-origin request refused' })
  }
}

export async function readJsonBody<T = unknown>(event: H3Event): Promise<T> {
  if (!getRequestHeader(event, 'content-type')?.startsWith('application/json')) {
    throw createError({ statusCode: 415, statusMessage: 'Expected application/json' })
  }
  return (await readBody(event).catch(() => null)) as T
}

export function oauthRedirectUri(event: H3Event): string {
  const trust = authState().trustProxy
  const url = getRequestURL(event, { xForwardedHost: trust, xForwardedProto: trust })
  return `${url.protocol}//${url.host}/api/google/callback`
}

// ---- OAuth --------------------------------------------------------------------------------------

const OAUTH_SCOPES = ['openid', 'email', 'https://www.googleapis.com/auth/adwords']
const pendingStates = new Map<string, { redirectUri: string; user: string; expires: number }>()

export async function oauthStartUrl(event: H3Event, user: string): Promise<string> {
  const s = await readGoogleSettings()
  if (!s.oauthClientId || !s.oauthClientSecret) {
    throw createError({ statusCode: 409, statusMessage: 'Add the OAuth client ID and secret first' })
  }
  const now = Date.now()
  for (const [k, v] of pendingStates) if (v.expires < now) pendingStates.delete(k)
  const state = randomBytes(24).toString('base64url')
  const redirectUri = oauthRedirectUri(event)
  pendingStates.set(state, { redirectUri, user, expires: now + 10 * 60_000 })
  const q = new URLSearchParams({
    client_id: s.oauthClientId,
    redirect_uri: redirectUri,
    response_type: 'code',
    scope: OAUTH_SCOPES.join(' '),
    access_type: 'offline',
    prompt: 'consent',
    include_granted_scopes: 'true',
    state,
  })
  return `https://accounts.google.com/o/oauth2/v2/auth?${q}`
}

/** Single-use: a state works once and only within 10 minutes of being issued to a signed-in user. */
export function takeOAuthState(state: string): { redirectUri: string; user: string } | null {
  const entry = pendingStates.get(state)
  pendingStates.delete(state)
  if (!entry || entry.expires < Date.now()) return null
  return entry
}

export async function exchangeOAuthCode(code: string, redirectUri: string): Promise<void> {
  const s = await readGoogleSettings()
  const res = await googleFetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      code,
      client_id: s.oauthClientId ?? '',
      client_secret: s.oauthClientSecret ?? '',
      redirect_uri: redirectUri,
      grant_type: 'authorization_code',
    }),
  }, 'Google sign-in')
  const body = (await res.json().catch(() => null)) as { refresh_token?: string; id_token?: string } | null
  if (!res.ok || !body) throw new Error(googleErrorMessage(body, `Google sign-in failed (${res.status})`))
  if (!body.refresh_token) throw new Error('Google did not return a refresh token. Remove the app from your Google account permissions and connect again.')
  let email = ''
  try {
    const payload = JSON.parse(Buffer.from((body.id_token ?? '').split('.')[1] ?? '', 'base64url').toString('utf8'))
    email = typeof payload.email === 'string' ? payload.email : ''
  } catch {
    email = ''
  }
  await writeGoogleSettings({ refreshToken: body.refresh_token, connectedEmail: email })
}

export async function disconnectGoogle(): Promise<void> {
  const s = await readGoogleSettings()
  if (s.refreshToken) {
    await fetch('https://oauth2.googleapis.com/revoke', {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ token: s.refreshToken }),
    }).catch(() => {})
  }
  await writeGoogleSettings({ refreshToken: '', connectedEmail: '' })
}

let accessTokenCache: { token: string; expires: number } | null = null

async function accessToken(): Promise<string> {
  if (accessTokenCache && accessTokenCache.expires > Date.now() + 60_000) return accessTokenCache.token
  const s = await readGoogleSettings()
  if (!s.refreshToken) throw createError({ statusCode: 409, statusMessage: 'Connect your Google account first' })
  const res = await googleFetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      client_id: s.oauthClientId ?? '',
      client_secret: s.oauthClientSecret ?? '',
      refresh_token: s.refreshToken,
      grant_type: 'refresh_token',
    }),
  }, 'Google sign-in')
  const body = (await res.json().catch(() => null)) as { access_token?: string; expires_in?: number } | null
  if (!res.ok || !body?.access_token) {
    throw createError({ statusCode: 502, statusMessage: googleErrorMessage(body, 'Could not refresh the Google sign-in; connect again') })
  }
  accessTokenCache = { token: body.access_token, expires: Date.now() + (body.expires_in ?? 3600) * 1000 }
  return body.access_token
}

// ---- YouTube Data API ---------------------------------------------------------------------------

export interface ChannelOverview {
  fetchedAt: string
  channel: {
    id: string
    title: string
    handle: string
    url: string
    thumbnail: string
    subscribers: number | null
    views: number
    videos: number
  }
  videos: {
    id: string
    title: string
    publishedAt: string
    thumbnail: string
    duration: string
    views: number
    likes: number
    comments: number
  }[]
}

let overviewCache: { at: number; data: ChannelOverview } | null = null

async function youtubeGet(path: string, params: Record<string, string>, key: string) {
  const q = new URLSearchParams({ ...params, key })
  const res = await googleFetch(`https://www.googleapis.com/youtube/v3/${path}?${q}`, undefined, 'YouTube')
  const body = await res.json().catch(() => null)
  if (!res.ok) throw createError({ statusCode: 502, statusMessage: googleErrorMessage(body, `YouTube API error ${res.status}`) })
  return body as { items?: Record<string, any>[] }
}

/** Channel statistics and up to 50 latest uploads. Cached for 10 minutes to protect the API quota. */
export async function channelOverview(force = false): Promise<ChannelOverview> {
  const minAge = force ? 60_000 : 10 * 60_000
  if (overviewCache && Date.now() - overviewCache.at < minAge) return overviewCache.data
  const s = await readGoogleSettings()
  if (!s.youtubeApiKey) throw createError({ statusCode: 409, statusMessage: 'Add a YouTube Data API key first' })
  const handle = normalizeHandle(s.channelHandle) || DEFAULT_HANDLE
  const ch = await youtubeGet('channels', { part: 'snippet,statistics,contentDetails', forHandle: handle }, s.youtubeApiKey)
  const c = ch.items?.[0]
  if (!c) throw createError({ statusCode: 404, statusMessage: `No YouTube channel found for ${handle}` })
  const uploads = c.contentDetails?.relatedPlaylists?.uploads as string | undefined
  let videos: ChannelOverview['videos'] = []
  if (uploads) {
    const pl = await youtubeGet('playlistItems', { part: 'contentDetails', playlistId: uploads, maxResults: '50' }, s.youtubeApiKey)
    const ids = (pl.items ?? []).map((i) => i.contentDetails?.videoId).filter(Boolean)
    if (ids.length) {
      const vs = await youtubeGet('videos', { part: 'snippet,statistics,contentDetails', id: ids.join(','), maxResults: '50' }, s.youtubeApiKey)
      videos = (vs.items ?? []).map((v) => ({
        id: v.id,
        title: v.snippet?.title ?? v.id,
        publishedAt: v.snippet?.publishedAt ?? '',
        thumbnail: v.snippet?.thumbnails?.medium?.url ?? v.snippet?.thumbnails?.default?.url ?? '',
        duration: v.contentDetails?.duration ?? '',
        views: Number(v.statistics?.viewCount ?? 0),
        likes: Number(v.statistics?.likeCount ?? 0),
        comments: Number(v.statistics?.commentCount ?? 0),
      }))
      videos.sort((a, b) => b.publishedAt.localeCompare(a.publishedAt))
    }
  }
  const data: ChannelOverview = {
    fetchedAt: new Date().toISOString(),
    channel: {
      id: c.id,
      title: c.snippet?.title ?? handle,
      handle: c.snippet?.customUrl ?? handle,
      url: `https://www.youtube.com/${handle}`,
      thumbnail: c.snippet?.thumbnails?.high?.url ?? c.snippet?.thumbnails?.default?.url ?? '',
      subscribers: c.statistics?.hiddenSubscriberCount ? null : Number(c.statistics?.subscriberCount ?? 0),
      views: Number(c.statistics?.viewCount ?? 0),
      videos: Number(c.statistics?.videoCount ?? 0),
    },
    videos,
  }
  overviewCache = { at: Date.now(), data }
  return data
}

export interface HistoryPoint {
  t: string
  subscribers: number | null
  views: number
  videos: number
}

export async function appendHistory(o: ChannelOverview): Promise<void> {
  const path = cfg().historyPath
  await mkdir(dirname(path), { recursive: true })
  const p: HistoryPoint = { t: o.fetchedAt, subscribers: o.channel.subscribers, views: o.channel.views, videos: o.channel.videos }
  await appendFile(path, `${JSON.stringify(p)}\n`)
}

export async function readHistory(days = 90): Promise<HistoryPoint[]> {
  let text = ''
  try {
    text = await readFile(cfg().historyPath, 'utf8')
  } catch {
    return []
  }
  const since = Date.now() - days * 86_400_000
  return text
    .split('\n')
    .filter(Boolean)
    .map((l) => {
      try {
        return JSON.parse(l) as HistoryPoint
      } catch {
        return null
      }
    })
    .filter((p): p is HistoryPoint => Boolean(p && Date.parse(p.t) >= since))
}

// ---- Google Ads API -----------------------------------------------------------------------------

async function adsRequest(path: string, body: unknown) {
  const s = await readGoogleSettings()
  const customerId = cleanCustomerId(s.adsCustomerId)
  if (!s.adsDeveloperToken || !customerId) {
    throw createError({ statusCode: 409, statusMessage: 'Add the Google Ads developer token and customer ID first' })
  }
  const headers: Record<string, string> = {
    authorization: `Bearer ${await accessToken()}`,
    'developer-token': s.adsDeveloperToken,
    'content-type': 'application/json',
  }
  const login = cleanCustomerId(s.adsLoginCustomerId)
  if (login) headers['login-customer-id'] = login
  const res = await googleFetch(
    `https://googleads.googleapis.com/${cfg().apiVersion}/customers/${customerId}/${path}`,
    { method: 'POST', headers, body: JSON.stringify(body) },
    'Google Ads',
  )
  const json = await res.json().catch(() => null)
  if (!res.ok) {
    throw createError({ statusCode: res.status === 401 || res.status === 403 ? 502 : 400, statusMessage: googleErrorMessage(json, `Google Ads API error ${res.status}`) })
  }
  return { customerId, json: json as Record<string, any> }
}

async function adsSearch(query: string): Promise<Record<string, any>[]> {
  const out: Record<string, any>[] = []
  let pageToken: string | undefined
  do {
    const { json } = await adsRequest('googleAds:search', pageToken ? { query, pageToken } : { query })
    out.push(...(json.results ?? []))
    pageToken = json.nextPageToken
  } while (pageToken)
  return out
}

export async function adsAccount() {
  const rows = await adsSearch('SELECT customer.id, customer.descriptive_name, customer.currency_code, customer.time_zone FROM customer LIMIT 1')
  const c = rows[0]?.customer ?? {}
  return { id: String(c.id ?? ''), name: c.descriptiveName ?? '', currency: c.currencyCode ?? '', timeZone: c.timeZone ?? '' }
}

export async function adsCampaigns() {
  const meta = await adsSearch(
    "SELECT campaign.id, campaign.name, campaign.status, campaign.advertising_channel_type, campaign_budget.amount_micros FROM campaign WHERE campaign.status != 'REMOVED' AND campaign.advertising_channel_type IN ('DEMAND_GEN', 'VIDEO')",
  )
  const base = "FROM campaign WHERE campaign.status != 'REMOVED' AND campaign.advertising_channel_type IN ('DEMAND_GEN', 'VIDEO') AND segments.date DURING LAST_30_DAYS"
  const fields = 'campaign.id, campaign.name, campaign.status, campaign.advertising_channel_type, metrics.cost_micros, metrics.impressions, metrics.clicks, metrics.engagements'
  let hasViews = true
  let metricRows: Record<string, any>[]
  try {
    metricRows = await adsSearch(`SELECT ${fields}, metrics.video_trueview_views ${base}`)
  } catch {
    // The views metric's name changes between API versions; carry on without it.
    hasViews = false
    metricRows = await adsSearch(`SELECT ${fields} ${base}`)
  }
  const rows = campaignRows([...meta.map((m) => ({ campaign: m.campaign, metrics: {} })), ...metricRows], hasViews)
  const budgets = new Map(meta.map((m) => [String(m.campaign?.id), fromMicros(m.campaignBudget?.amountMicros)]))
  return rows.map((r) => ({ ...r, cost: fromMicros(r.costMicros), dailyBudget: budgets.get(r.id) ?? null }))
}

async function geoTargets(countries: string[]): Promise<string[]> {
  const list = countries.map((c) => `'${c.replace(/[^A-Z]/g, '')}'`).join(', ')
  const rows = await adsSearch(
    `SELECT geo_target_constant.resource_name, geo_target_constant.country_code FROM geo_target_constant WHERE geo_target_constant.country_code IN (${list}) AND geo_target_constant.target_type = 'Country' AND geo_target_constant.status = 'ENABLED'`,
  )
  const found = rows.map((r) => r.geoTargetConstant?.resourceName as string).filter(Boolean)
  if (found.length === 0) throw createError({ statusCode: 400, statusMessage: 'Google did not recognise those countries' })
  return found
}

async function channelLogoBase64(url: string): Promise<string> {
  if (!/^https:\/\/[a-z0-9.-]+\.(ggpht|googleusercontent|ytimg)\.com\//.test(url)) {
    throw createError({ statusCode: 400, statusMessage: 'The channel picture is not available to use as the ad logo' })
  }
  const res = await googleFetch(url, undefined, 'the channel picture')
  if (!res.ok) throw createError({ statusCode: 502, statusMessage: `Could not download the channel picture (${res.status})` })
  return Buffer.from(await res.arrayBuffer()).toString('base64')
}

export async function launchDemandGen(input: LaunchInput, user: string) {
  const overview = await channelOverview()
  const [geo, logo] = await Promise.all([geoTargets(input.countries), channelLogoBase64(overview.channel.thumbnail)])
  const s = await readGoogleSettings()
  const stamp = new Date().toISOString().replace(/[-:]/g, '').slice(0, 15)
  const ops = demandGenOperations({ customerId: cleanCustomerId(s.adsCustomerId), input, geoTargetConstants: geo, logoImageBase64: logo, stamp })
  const { json } = await adsRequest('googleAds:mutate', { mutateOperations: ops })
  const campaign = (json.mutateOperationResponses ?? []).map((r: any) => r.campaignResult?.resourceName).find(Boolean) as string | undefined
  const campaignId = campaign?.split('/').pop() ?? ''
  await logAdsAction({ action: 'launch', user, campaignId, name: input.name, videoId: input.videoId, dailyBudget: input.dailyBudget, countries: input.countries })
  return { campaignId, status: 'PAUSED' }
}

export async function setCampaignStatus(campaignId: string, status: 'ENABLED' | 'PAUSED', user: string) {
  const s = await readGoogleSettings()
  const resourceName = `customers/${cleanCustomerId(s.adsCustomerId)}/campaigns/${campaignId}`
  await adsRequest('campaigns:mutate', { operations: [{ update: { resourceName, status }, updateMask: 'status' }] })
  await logAdsAction({ action: status === 'ENABLED' ? 'start' : 'pause', user, campaignId })
  return { campaignId, status }
}

async function logAdsAction(entry: Record<string, unknown>) {
  const path = cfg().actionsPath
  await mkdir(dirname(path), { recursive: true })
  await appendFile(path, `${JSON.stringify({ t: new Date().toISOString(), ...entry })}\n`).catch(() => {})
}

export function maxDailyBudget() {
  return cfg().maxDailyBudget
}
