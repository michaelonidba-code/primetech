// Pure helpers for the YouTube growth console: input validation, Google Ads mutate operations and
// error parsing. No Nitro globals here, so test/google-ads.test.ts can import this file directly.

/** Demand Gen text limits (characters). */
export const LIMITS = { headline: 40, longHeadline: 90, description: 90, businessName: 25, name: 120 } as const

/** Countries offered in the launch form, by ISO code. Google resolves them to geo target constants. */
export const COUNTRIES: Record<string, string> = {
  NG: 'Nigeria',
  GH: 'Ghana',
  BJ: 'Benin',
  TG: 'Togo',
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

export interface LaunchInput {
  videoId: string
  name: string
  dailyBudget: number
  countries: string[]
  headline: string
  longHeadline: string
  description: string
  businessName: string
  finalUrl: string
}

const YT_ID = /^[A-Za-z0-9_-]{11}$/

/** Checks a launch request from the browser. Returns the cleaned value or a list of problems. */
export function validateLaunch(raw: unknown, maxDailyBudget = 1000): { value?: LaunchInput; errors: string[] } {
  const errors: string[] = []
  const r = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>
  const text = (k: string) => (typeof r[k] === 'string' ? (r[k] as string).trim() : '')
  const videoId = text('videoId')
  if (!YT_ID.test(videoId)) errors.push('Pick a video to promote.')
  const name = text('name')
  if (!name) errors.push('Give the campaign a name.')
  else if (name.length > LIMITS.name) errors.push(`Campaign name: at most ${LIMITS.name} characters.`)
  const dailyBudget = Number(r.dailyBudget)
  if (!Number.isFinite(dailyBudget) || dailyBudget < 1) errors.push('Daily budget must be at least 1.')
  else if (dailyBudget > maxDailyBudget) errors.push(`Daily budget is capped at ${maxDailyBudget} here; change it in Google Ads if you need more.`)
  const countries = Array.isArray(r.countries) ? [...new Set(r.countries.filter((c): c is string => typeof c === 'string' && c in COUNTRIES))] : []
  if (countries.length === 0) errors.push('Choose at least one country.')
  const fields: [keyof typeof LIMITS, string, string][] = [
    ['headline', text('headline'), 'Headline'],
    ['longHeadline', text('longHeadline'), 'Long headline'],
    ['description', text('description'), 'Description'],
    ['businessName', text('businessName'), 'Business name'],
  ]
  for (const [key, value, label] of fields) {
    if (!value) errors.push(`${label} is required.`)
    else if (value.length > LIMITS[key]) errors.push(`${label}: at most ${LIMITS[key]} characters (now ${value.length}).`)
  }
  const finalUrl = text('finalUrl')
  try {
    const u = new URL(finalUrl)
    if (u.protocol !== 'https:') errors.push('Final URL must start with https://')
  } catch {
    errors.push('Final URL is not a valid web address.')
  }
  if (errors.length) return { errors }
  return {
    errors,
    value: {
      videoId,
      name,
      dailyBudget: Math.round(dailyBudget * 100) / 100,
      countries,
      headline: text('headline'),
      longHeadline: text('longHeadline'),
      description: text('description'),
      businessName: text('businessName'),
      finalUrl,
    },
  }
}

export const toMicros = (amount: number) => String(Math.round(amount * 1_000_000))
export const fromMicros = (micros: string | number | undefined) => Number(micros ?? 0) / 1_000_000

/** Customer IDs may be typed as 123-456-7890; the API wants digits only. */
export function cleanCustomerId(id: string | undefined): string {
  return (id ?? '').replace(/\D/g, '')
}

/** Accepts @handle, handle, or a youtube.com/@handle URL; returns "@handle" or "" if unusable. */
export function normalizeHandle(input: string | undefined): string {
  let s = (input ?? '').trim()
  const m = s.match(/youtube\.com\/(@[A-Za-z0-9._-]+)/)
  if (m) s = m[1]!
  if (!s) return ''
  if (!s.startsWith('@')) s = `@${s}`
  return /^@[A-Za-z0-9._-]{3,100}$/.test(s) ? s : ''
}

/** Shows only the last 4 characters of a secret. */
export function maskSecret(value: string | undefined): string {
  if (!value) return ''
  return value.length <= 4 ? '••••' : `••••${value.slice(-4)}`
}

/** Pulls a readable message out of a Google API error body. */
export function googleErrorMessage(body: unknown, fallback: string): string {
  const b = body as {
    error?: { message?: string; details?: { errors?: { message?: string; location?: unknown }[] }[] } | string
    error_description?: string
  } | null
  if (!b) return fallback
  if (typeof b.error === 'string') return b.error_description ? `${b.error}: ${b.error_description}` : b.error
  const detailed = b.error?.details?.flatMap((d) => d.errors ?? []).map((e) => e.message).filter(Boolean) ?? []
  if (detailed.length) return [...new Set(detailed)].join(' ')
  return b.error?.message || fallback
}

export interface DemandGenPlan {
  customerId: string
  input: LaunchInput
  geoTargetConstants: string[]
  logoImageBase64: string
  stamp: string
}

/**
 * One atomic GoogleAdsService.Mutate request that creates a paused, YouTube-only Demand Gen
 * campaign with a video ad. Temporary negative IDs link the pieces; order matters.
 */
export function demandGenOperations(plan: DemandGenPlan): Record<string, unknown>[] {
  const c = `customers/${plan.customerId}`
  const budget = `${c}/campaignBudgets/-1`
  const campaign = `${c}/campaigns/-2`
  const adGroup = `${c}/adGroups/-3`
  const video = `${c}/assets/-4`
  const logo = `${c}/assets/-5`
  const { input, stamp } = plan
  return [
    {
      campaignBudgetOperation: {
        create: {
          resourceName: budget,
          name: `${input.name} budget ${stamp}`,
          amountMicros: toMicros(input.dailyBudget),
          deliveryMethod: 'STANDARD',
          explicitlyShared: false,
        },
      },
    },
    {
      campaignOperation: {
        create: {
          resourceName: campaign,
          name: input.name,
          advertisingChannelType: 'DEMAND_GEN',
          // Always created paused: spending starts only when someone presses Start.
          status: 'PAUSED',
          campaignBudget: budget,
          targetSpend: {},
          containsEuPoliticalAdvertising: 'DOES_NOT_CONTAIN_EU_POLITICAL_ADVERTISING',
        },
      },
    },
    {
      adGroupOperation: {
        create: {
          resourceName: adGroup,
          name: `${input.name} – YouTube`,
          campaign,
          status: 'ENABLED',
          demandGenAdGroupSettings: {
            channelControls: {
              selectedChannels: {
                youtubeInFeed: true,
                youtubeInStream: true,
                youtubeShorts: true,
                discover: false,
                gmail: false,
                display: false,
                maps: false,
              },
            },
          },
        },
      },
    },
    ...plan.geoTargetConstants.map((geo) => ({
      adGroupCriterionOperation: { create: { adGroup, location: { geoTargetConstant: geo } } },
    })),
    {
      assetOperation: {
        create: { resourceName: video, name: `YouTube ${input.videoId} ${stamp}`, youtubeVideoAsset: { youtubeVideoId: input.videoId } },
      },
    },
    {
      assetOperation: {
        create: { resourceName: logo, name: `Channel logo ${stamp}`, imageAsset: { data: plan.logoImageBase64 } },
      },
    },
    {
      adGroupAdOperation: {
        create: {
          adGroup,
          status: 'ENABLED',
          ad: {
            name: `${input.name} video ad`,
            finalUrls: [input.finalUrl],
            demandGenVideoResponsiveAd: {
              headlines: [{ text: input.headline }],
              longHeadlines: [{ text: input.longHeadline }],
              descriptions: [{ text: input.description }],
              businessName: { text: input.businessName },
              videos: [{ asset: video }],
              logoImages: [{ asset: logo }],
            },
          },
        },
      },
    },
  ]
}

export interface CampaignRow {
  id: string
  name: string
  status: string
  type: string
  costMicros: number
  impressions: number
  clicks: number
  videoViews: number | null
  engagements: number
}

/** Folds GoogleAdsService.Search results (any number of rows per campaign) into one row per campaign. */
export function campaignRows(results: unknown[], hasVideoViews: boolean): CampaignRow[] {
  const byId = new Map<string, CampaignRow>()
  for (const raw of results) {
    const r = raw as {
      campaign?: { id?: string; name?: string; status?: string; advertisingChannelType?: string }
      metrics?: Record<string, string | number | undefined>
    }
    const id = String(r.campaign?.id ?? '')
    if (!id) continue
    const row = byId.get(id) ?? {
      id,
      name: r.campaign?.name ?? id,
      status: r.campaign?.status ?? 'UNKNOWN',
      type: r.campaign?.advertisingChannelType ?? '',
      costMicros: 0,
      impressions: 0,
      clicks: 0,
      videoViews: hasVideoViews ? 0 : null,
      engagements: 0,
    }
    const m = r.metrics ?? {}
    row.costMicros += Number(m.costMicros ?? 0)
    row.impressions += Number(m.impressions ?? 0)
    row.clicks += Number(m.clicks ?? 0)
    row.engagements += Number(m.engagements ?? 0)
    if (hasVideoViews) row.videoViews = (row.videoViews ?? 0) + Number(m.videoTrueviewViews ?? 0)
    byId.set(id, row)
  }
  return [...byId.values()].sort((a, b) => b.costMicros - a.costMicros || a.name.localeCompare(b.name))
}
