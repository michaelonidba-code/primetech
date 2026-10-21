// Pure rules for sponsor campaigns: validation, which campaigns are live, the catalog the ad server
// reads, the public creative manifest the ad tag reads, and MP4 inspection. No Nitro globals, so
// test/sponsors.test.ts imports this file directly.

export const SPONSOR_COUNTRIES: Record<string, string> = {
  NG: 'Nigeria',
  GH: 'Ghana',
  KE: 'Kenya',
  ZA: 'South Africa',
  US: 'United States',
  GB: 'United Kingdom',
  CA: 'Canada',
  IE: 'Ireland',
  AE: 'United Arab Emirates',
  QA: 'Qatar',
  AU: 'Australia',
  DE: 'Germany',
  FR: 'France',
}

export type SponsorStatus = 'draft' | 'approved' | 'paused'

export interface SponsorCreative {
  id: string
  file: string
  durationSeconds: number
  bytes: number
  fastStart: boolean
  uploadedAt: string
}

export interface SponsorCampaign {
  id: string
  sponsor: string
  name: string
  clickUrl: string
  cpm: number
  dailyBudget: number
  countries: string[]
  startDate: string
  endDate: string | null
  status: SponsorStatus
  creative: SponsorCreative | null
  createdAt: string
  updatedAt: string
  approvedAt: string | null
  approvedBy: string | null
}

export interface SponsorLimits {
  floorCpm: number
  maxCpm: number
  maxDailyBudget: number
}

export const DEFAULT_LIMITS: SponsorLimits = { floorCpm: 5, maxCpm: 200, maxDailyBudget: 1000 }

const DATE = /^\d{4}-\d{2}-\d{2}$/

function validDate(s: string): boolean {
  if (!DATE.test(s)) return false
  const d = new Date(`${s}T00:00:00Z`)
  return !Number.isNaN(d.getTime()) && d.toISOString().slice(0, 10) === s
}

export type CampaignInput = Pick<
  SponsorCampaign,
  'sponsor' | 'name' | 'clickUrl' | 'cpm' | 'dailyBudget' | 'countries' | 'startDate' | 'endDate'
>

/** Validates the editable fields of a campaign. */
export function validateCampaign(raw: unknown, limits: SponsorLimits = DEFAULT_LIMITS): { value?: CampaignInput; errors: string[] } {
  const errors: string[] = []
  const r = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>
  const text = (k: string) => (typeof r[k] === 'string' ? (r[k] as string).trim() : '')
  const sponsor = text('sponsor')
  if (!sponsor) errors.push('Sponsor name is required.')
  else if (sponsor.length > 60) errors.push('Sponsor name: at most 60 characters.')
  const name = text('name')
  if (!name) errors.push('Campaign name is required.')
  else if (name.length > 100) errors.push('Campaign name: at most 100 characters.')
  const clickUrl = text('clickUrl')
  try {
    const u = new URL(clickUrl)
    if (u.protocol !== 'https:') errors.push('Link must start with https://')
  } catch {
    errors.push('Link is not a valid web address.')
  }
  const cpm = Number(r.cpm)
  if (!Number.isFinite(cpm) || cpm < limits.floorCpm) errors.push(`Price per 1,000 views must be at least $${limits.floorCpm}.`)
  else if (cpm > limits.maxCpm) errors.push(`Price per 1,000 views is capped at $${limits.maxCpm}.`)
  const dailyBudget = Number(r.dailyBudget)
  if (!Number.isFinite(dailyBudget) || dailyBudget < 1) errors.push('Daily budget must be at least $1.')
  else if (dailyBudget > limits.maxDailyBudget) errors.push(`Daily budget is capped at $${limits.maxDailyBudget}.`)
  const countries = Array.isArray(r.countries)
    ? [...new Set(r.countries.filter((c): c is string => typeof c === 'string' && c in SPONSOR_COUNTRIES))]
    : []
  const startDate = text('startDate')
  if (!validDate(startDate)) errors.push('Start date is required (YYYY-MM-DD).')
  const endRaw = text('endDate')
  const endDate = endRaw ? endRaw : null
  if (endDate && !validDate(endDate)) errors.push('End date must be a date (YYYY-MM-DD) or empty.')
  else if (endDate && validDate(startDate) && endDate < startDate) errors.push('End date is before the start date.')
  if (errors.length) return { errors }
  return {
    errors,
    value: {
      sponsor,
      name,
      clickUrl,
      cpm: Math.round(cpm * 100) / 100,
      dailyBudget: Math.round(dailyBudget * 100) / 100,
      countries,
      startDate,
      endDate,
    },
  }
}

/** Why a campaign is or isn't serving today (UTC dates). */
export function servingState(c: SponsorCampaign, today: string): { serving: boolean; reason: string } {
  if (!c.creative) return { serving: false, reason: 'Needs a video' }
  if (c.status === 'draft') return { serving: false, reason: 'Waiting for your approval' }
  if (c.status === 'paused') return { serving: false, reason: 'Paused' }
  if (today < c.startDate) return { serving: false, reason: `Starts ${c.startDate}` }
  if (c.endDate && today > c.endDate) return { serving: false, reason: `Ended ${c.endDate}` }
  return { serving: true, reason: 'Live' }
}

export const sponsorIds = {
  campaign: (c: SponsorCampaign) => `sp-${c.id}`,
  lineItem: (c: SponsorCampaign) => `sp-${c.id}-li`,
  advertiser: (c: SponsorCampaign) => `spon-${sponsorSlug(c.sponsor)}`,
  ad: (cr: SponsorCreative) => `ad-${cr.id}`,
}

export function sponsorSlug(s: string): string {
  return (
    s
      .toLowerCase()
      .normalize('NFKD')
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, 40) || 'sponsor'
  )
}

/**
 * The catalog the ad server loads (CATALOG_FILE): live campaigns only, as guaranteed sponsorships.
 * The daily budget is split across pacing periods (30 minutes by default).
 */
export function buildCatalog(campaigns: SponsorCampaign[], today: string, pacingPeriodMinutes = 30) {
  const periodsPerDay = Math.max(1, Math.round(1440 / pacingPeriodMinutes))
  return {
    generatedAt: new Date().toISOString(),
    campaigns: campaigns
      .filter((c) => servingState(c, today).serving)
      .map((c) => ({
        campaignId: sponsorIds.campaign(c),
        name: c.name,
        advertiserId: sponsorIds.advertiser(c),
        advertiserName: c.sponsor,
        lineItems: [
          {
            lineItemId: sponsorIds.lineItem(c),
            tier: 'GUARANTEED',
            cpmMicros: Math.round(c.cpm * 1_000_000),
            periodBudgetMicros: Math.max(1, Math.round((c.dailyBudget * 1_000_000) / periodsPerDay)),
            countries: c.countries,
            deviceTypes: [],
            frequencyCapPerHour: 4,
            creatives: [{ adId: sponsorIds.ad(c.creative!), creativeId: c.creative!.id, durationSeconds: c.creative!.durationSeconds }],
          },
        ],
      })),
  }
}

/** The public creative list the ad tag reads: only what a viewer would see anyway. */
export function buildManifest(campaigns: SponsorCampaign[], today: string) {
  const creatives: Record<string, { src: string; clickUrl: string; sponsor: string; durationSeconds: number }> = {}
  for (const c of campaigns) {
    if (!servingState(c, today).serving || !c.creative) continue
    creatives[c.creative.id] = {
      src: `/media/${c.creative.file}`,
      clickUrl: c.clickUrl,
      sponsor: c.sponsor,
      durationSeconds: c.creative.durationSeconds,
    }
  }
  return { updatedAt: new Date().toISOString(), creatives }
}

/** Reads `length` bytes at `offset`; returns fewer at end of file. */
export type ByteReader = (offset: number, length: number) => Promise<Buffer>

/**
 * Checks an MP4 and reads its duration from the movie header (mvhd). `fastStart` is true when the
 * header comes before the media data, which lets browsers start playing before the whole file loads.
 */
export async function inspectMp4(read: ByteReader, size: number): Promise<{ durationSeconds: number; fastStart: boolean }> {
  const head = await read(0, 12)
  if (head.length < 12 || head.toString('latin1', 4, 8) !== 'ftyp') throw new Error('Not an MP4 video file.')
  let offset = 0
  let moov: { start: number; size: number } | null = null
  let mdatAt = -1
  while (offset + 8 <= size) {
    const h = await read(offset, 16)
    if (h.length < 8) break
    let boxSize = h.readUInt32BE(0)
    const type = h.toString('latin1', 4, 8)
    let header = 8
    if (boxSize === 1) {
      if (h.length < 16) break
      boxSize = Number(h.readBigUInt64BE(8))
      header = 16
    } else if (boxSize === 0) {
      boxSize = size - offset
    }
    if (boxSize < header) throw new Error('The MP4 file is damaged.')
    if (type === 'moov') moov = { start: offset + header, size: boxSize - header }
    if (type === 'mdat' && mdatAt < 0) mdatAt = offset
    offset += boxSize
  }
  if (!moov) throw new Error('The MP4 has no movie header (moov); re-export it.')
  let p = moov.start
  const end = moov.start + moov.size
  while (p + 8 <= end) {
    const h = await read(p, 8)
    const boxSize = h.readUInt32BE(0)
    const type = h.toString('latin1', 4, 8)
    if (boxSize < 8) break
    if (type === 'mvhd') {
      const body = await read(p + 8, 32)
      const version = body.readUInt8(0)
      const timescale = version === 1 ? body.readUInt32BE(20) : body.readUInt32BE(12)
      const duration = version === 1 ? Number(body.readBigUInt64BE(24)) : body.readUInt32BE(16)
      if (!timescale) throw new Error('The MP4 header has no timescale.')
      const seconds = Math.round(duration / timescale)
      return { durationSeconds: seconds, fastStart: mdatAt < 0 || moov.start < mdatAt }
    }
    p += boxSize
  }
  throw new Error('Could not read the video length; re-export the MP4.')
}
