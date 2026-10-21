// Sponsor campaigns: the store (private), the catalog the ad server reads, the public creative
// manifest and video files the ad tag uses, uploads, and delivery figures from Druid.
import { randomBytes } from 'node:crypto'
import { createWriteStream } from 'node:fs'
import { appendFile, mkdir, open, readFile, rename, rm, stat, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { Transform } from 'node:stream'
import { pipeline } from 'node:stream/promises'
import type { H3Event } from 'h3'
import {
  buildCatalog,
  buildManifest,
  inspectMp4,
  servingState,
  sponsorIds,
  validateCampaign,
  type CampaignInput,
  type SponsorCampaign,
  type SponsorLimits,
} from './sponsors-core'

function cfg() {
  const c = useRuntimeConfig()
  return {
    storePath: c.sponsorsStorePath as string,
    catalogPath: c.sponsorsCatalogPath as string,
    mediaDir: c.sponsorsMediaDir as string,
    actionsPath: c.sponsorsActionsLogPath as string,
    pacingPeriodMinutes: Number(c.pacingPeriodMinutes) || 30,
    maxUploadBytes: (Number(c.sponsorMaxUploadMb) || 80) * 1024 * 1024,
    limits: {
      floorCpm: Number(c.sponsorFloorCpm) || 5,
      maxCpm: 200,
      maxDailyBudget: Number(c.adsMaxDailyBudget) || 1000,
    } satisfies SponsorLimits,
    druidUrl: (c.druidUrl as string).replace(/\/+$/, ''),
  }
}

export const sponsorLimits = () => cfg().limits
export const sponsorToday = () => new Date().toISOString().slice(0, 10)

let cache: SponsorCampaign[] | null = null

export async function readSponsors(): Promise<SponsorCampaign[]> {
  if (cache) return cache
  try {
    cache = JSON.parse(await readFile(cfg().storePath, 'utf8')) as SponsorCampaign[]
  } catch {
    cache = []
  }
  return cache
}

async function writeAtomic(path: string, text: string, mode = 0o644) {
  await mkdir(dirname(path), { recursive: true })
  const tmp = `${path}.${process.pid}.${randomBytes(4).toString('hex')}.tmp`
  await writeFile(tmp, text, { mode })
  await rename(tmp, path)
}

/** Saves the store, then republishes the ad server catalog and the public manifest. */
async function saveSponsors(list: SponsorCampaign[]) {
  await writeAtomic(cfg().storePath, JSON.stringify(list, null, 2), 0o600)
  cache = list
  await publishSponsors()
}

/** Rewrites catalog.json and creatives.json from the store. Also run hourly for start/end dates. */
export async function publishSponsors() {
  const list = await readSponsors()
  const day = sponsorToday()
  const { catalogPath, mediaDir, pacingPeriodMinutes } = cfg()
  const catalog = JSON.stringify(buildCatalog(list, day, pacingPeriodMinutes), null, 2)
  const manifest = JSON.stringify(buildManifest(list, day))
  // Only rewrite on change, so the ad server reloads (and re-announces) only when something changed.
  const changed = async (path: string, text: string, strip: (s: string) => string) => {
    try {
      return strip(await readFile(path, 'utf8')) !== strip(text)
    } catch {
      return true
    }
  }
  const noStamp = (s: string) => s.replace(/"(generatedAt|updatedAt)":\s*"[^"]*"/, '')
  if (await changed(catalogPath, catalog, noStamp)) await writeAtomic(catalogPath, catalog)
  const manifestPath = join(mediaDir, 'creatives.json')
  if (await changed(manifestPath, manifest, noStamp)) await writeAtomic(manifestPath, manifest)
}

async function logAction(entry: Record<string, unknown>) {
  const path = cfg().actionsPath
  await mkdir(dirname(path), { recursive: true })
  await appendFile(path, `${JSON.stringify({ t: new Date().toISOString(), ...entry })}\n`).catch(() => {})
}

export function sponsorWithState(c: SponsorCampaign) {
  const s = servingState(c, sponsorToday())
  return { ...c, serving: s.serving, servingReason: s.reason, campaignId: sponsorIds.campaign(c) }
}

export async function saveCampaign(raw: unknown, user: string) {
  const r = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>
  const { value, errors } = validateCampaign(r, cfg().limits)
  if (!value) throw createError({ statusCode: 400, statusMessage: errors.join(' ') })
  const list = [...(await readSponsors())]
  const now = new Date().toISOString()
  const id = typeof r.id === 'string' ? r.id : ''
  if (id) {
    const i = list.findIndex((c) => c.id === id)
    if (i < 0) throw createError({ statusCode: 404, statusMessage: 'No such campaign' })
    const before = list[i]!
    // Changing what runs, or what it costs, needs approving again.
    const material: (keyof CampaignInput)[] = ['sponsor', 'clickUrl', 'cpm', 'dailyBudget', 'countries']
    const needsReapproval = before.status === 'approved' && material.some((k) => JSON.stringify(before[k]) !== JSON.stringify(value[k]))
    list[i] = { ...before, ...value, status: needsReapproval ? 'draft' : before.status, updatedAt: now }
    await saveSponsors(list)
    await logAction({ action: 'edit', user, id, reapproval: needsReapproval })
    return sponsorWithState(list[i]!)
  }
  const campaign: SponsorCampaign = {
    id: randomBytes(5).toString('hex'),
    ...value,
    status: 'draft',
    creative: null,
    createdAt: now,
    updatedAt: now,
    approvedAt: null,
    approvedBy: null,
  }
  list.push(campaign)
  await saveSponsors(list)
  await logAction({ action: 'create', user, id: campaign.id, sponsor: campaign.sponsor })
  return sponsorWithState(campaign)
}

export async function setSponsorStatus(id: string, status: 'approved' | 'paused', user: string) {
  const list = [...(await readSponsors())]
  const i = list.findIndex((c) => c.id === id)
  if (i < 0) throw createError({ statusCode: 404, statusMessage: 'No such campaign' })
  const c = list[i]!
  if (status === 'approved' && !c.creative) throw createError({ statusCode: 409, statusMessage: 'Upload the video before approving.' })
  const now = new Date().toISOString()
  list[i] = status === 'approved' ? { ...c, status, approvedAt: now, approvedBy: user, updatedAt: now } : { ...c, status, updatedAt: now }
  await saveSponsors(list)
  await logAction({ action: status === 'approved' ? 'approve' : 'pause', user, id })
  return sponsorWithState(list[i]!)
}

export async function deleteSponsor(id: string, user: string) {
  const list = await readSponsors()
  const c = list.find((x) => x.id === id)
  if (!c) throw createError({ statusCode: 404, statusMessage: 'No such campaign' })
  await saveSponsors(list.filter((x) => x.id !== id))
  if (c.creative) await rm(join(cfg().mediaDir, c.creative.file), { force: true })
  await logAction({ action: 'delete', user, id, sponsor: c.sponsor })
}

/** Streams an MP4 upload to disk (never all in memory), checks it, and attaches it to the campaign. */
export async function uploadCreative(event: H3Event, id: string, user: string) {
  const { mediaDir, maxUploadBytes } = cfg()
  const list = [...(await readSponsors())]
  const i = list.findIndex((c) => c.id === id)
  if (i < 0) throw createError({ statusCode: 404, statusMessage: 'No such campaign' })
  const type = getRequestHeader(event, 'content-type') ?? ''
  if (!type.startsWith('video/mp4')) throw createError({ statusCode: 415, statusMessage: 'Upload an MP4 video (video/mp4).' })
  const declared = Number(getRequestHeader(event, 'content-length') ?? 0)
  if (declared > maxUploadBytes) throw createError({ statusCode: 413, statusMessage: `Videos can be at most ${maxUploadBytes / 1024 / 1024} MB.` })

  await mkdir(mediaDir, { recursive: true })
  const tmp = join(mediaDir, `.upload-${randomBytes(6).toString('hex')}.tmp`)
  let received = 0
  const limiter = new Transform({
    transform(chunk: Buffer, _enc, done) {
      received += chunk.length
      done(received > maxUploadBytes ? new Error('too large') : null, chunk)
    },
  })
  try {
    await pipeline(event.node.req, limiter, createWriteStream(tmp, { mode: 0o644 }))
  } catch (err) {
    await rm(tmp, { force: true })
    const tooLarge = err instanceof Error && err.message === 'too large'
    throw createError({ statusCode: tooLarge ? 413 : 400, statusMessage: tooLarge ? `Videos can be at most ${maxUploadBytes / 1024 / 1024} MB.` : 'The upload was interrupted.' })
  }
  let info: { durationSeconds: number; fastStart: boolean }
  const fh = await open(tmp, 'r')
  try {
    const size = (await fh.stat()).size
    info = await inspectMp4(async (offset, length) => {
      const buf = Buffer.alloc(length)
      const { bytesRead } = await fh.read(buf, 0, length, offset)
      return buf.subarray(0, bytesRead)
    }, size)
  } catch (err) {
    await fh.close()
    await rm(tmp, { force: true })
    throw createError({ statusCode: 400, statusMessage: err instanceof Error ? err.message : 'Not a usable MP4.' })
  }
  await fh.close()
  if (info.durationSeconds < 1 || info.durationSeconds > 60) {
    await rm(tmp, { force: true })
    throw createError({ statusCode: 400, statusMessage: `Sponsor videos must be 1 to 60 seconds long (this one is ${info.durationSeconds}s).` })
  }
  const creativeId = `cr-${id}-${randomBytes(3).toString('hex')}`
  const file = `${creativeId}.mp4`
  await rename(tmp, join(mediaDir, file))
  const bytes = (await stat(join(mediaDir, file))).size
  const before = list[i]!
  const now = new Date().toISOString()
  list[i] = {
    ...before,
    creative: { id: creativeId, file, durationSeconds: info.durationSeconds, bytes, fastStart: info.fastStart, uploadedAt: now },
    // A new video always needs approving before it runs.
    status: before.status === 'approved' ? 'draft' : before.status,
    updatedAt: now,
  }
  await saveSponsors(list)
  if (before.creative) await rm(join(mediaDir, before.creative.file), { force: true })
  await logAction({ action: 'upload', user, id, creativeId, bytes, durationSeconds: info.durationSeconds })
  return sponsorWithState(list[i]!)
}

export interface SponsorDelivery {
  campaignId: string
  impressions: number
  completes: number
  clicks: number
  spend: number
}

/** Delivery per sponsor campaign over the last `days`, excluding invalid traffic. */
export async function sponsorDelivery(days = 30): Promise<{ available: boolean; rows: SponsorDelivery[]; error?: string }> {
  const d = Math.min(90, Math.max(1, Math.trunc(days)))
  const query = `
    SELECT campaignId,
      SUM(events) FILTER (WHERE type = 'IMPRESSION' AND ivt = 0) AS impressions,
      SUM(events) FILTER (WHERE type = 'COMPLETE' AND ivt = 0) AS completes,
      SUM(events) FILTER (WHERE type = 'CLICK' AND ivt = 0) AS clicks,
      SUM(priceMicros) FILTER (WHERE type = 'IMPRESSION' AND ivt = 0) AS spendMicros
    FROM ad_events
    WHERE __time >= CURRENT_TIMESTAMP - INTERVAL '${d}' DAY AND campaignId LIKE 'sp-%'
    GROUP BY 1`
  try {
    const rows = await $fetch<Record<string, number | string | null>[]>(`${cfg().druidUrl}/druid/v2/sql`, {
      method: 'POST',
      body: { query },
      timeout: 5000,
    })
    return {
      available: true,
      rows: rows.map((r) => ({
        campaignId: String(r.campaignId),
        impressions: Number(r.impressions ?? 0),
        completes: Number(r.completes ?? 0),
        clicks: Number(r.clicks ?? 0),
        spend: Number(r.spendMicros ?? 0) / 1_000_000,
      })),
    }
  } catch (err) {
    return { available: false, rows: [], error: err instanceof Error ? err.message : String(err) }
  }
}
