import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  buildCatalog,
  buildManifest,
  inspectMp4,
  servingState,
  sponsorSlug,
  validateCampaign,
  type SponsorCampaign,
} from '../server/utils/sponsors-core.ts'

const input = {
  sponsor: 'The One Dollar World',
  name: 'Launch promo',
  clickUrl: 'https://1world1dollar.com/launch',
  cpm: 20,
  dailyBudget: 24,
  countries: ['NG', 'US', 'XX'],
  startDate: '2026-10-21',
  endDate: '2026-11-30',
}

function campaign(over: Partial<SponsorCampaign> = {}): SponsorCampaign {
  return {
    id: 'a1b2c3d4e5',
    ...validateCampaign(input).value!,
    status: 'approved',
    creative: { id: 'cr-a1b2c3d4e5-ff00aa', file: 'cr-a1b2c3d4e5-ff00aa.mp4', durationSeconds: 30, bytes: 1000, fastStart: true, uploadedAt: '' },
    createdAt: '',
    updatedAt: '',
    approvedAt: '',
    approvedBy: 'admin',
    ...over,
  }
}

test('campaign validation keeps known countries and enforces the floor and limits', () => {
  const { value, errors } = validateCampaign(input)
  assert.deepEqual(errors, [])
  assert.deepEqual(value?.countries, ['NG', 'US'])
  const bad = validateCampaign({ ...input, cpm: 2, dailyBudget: 5000, clickUrl: 'http://x', startDate: '2026-02-30', endDate: 'soon' })
  assert.equal(bad.value, undefined)
  assert.ok(bad.errors.some((e) => e.includes('at least $5')))
  assert.ok(bad.errors.some((e) => e.includes('capped at $1000')))
  assert.ok(bad.errors.some((e) => e.includes('https://')))
  assert.ok(bad.errors.some((e) => e.startsWith('Start date')))
  assert.ok(validateCampaign({ ...input, endDate: '2026-10-01' }).errors.some((e) => e.includes('before the start')))
})

test('only approved campaigns with a video, within their dates, serve', () => {
  assert.equal(servingState(campaign(), '2026-10-25').serving, true)
  assert.equal(servingState(campaign({ status: 'draft' }), '2026-10-25').reason, 'Waiting for your approval')
  assert.equal(servingState(campaign({ status: 'paused' }), '2026-10-25').serving, false)
  assert.equal(servingState(campaign({ creative: null }), '2026-10-25').reason, 'Needs a video')
  assert.equal(servingState(campaign(), '2026-10-20').reason, 'Starts 2026-10-21')
  assert.equal(servingState(campaign(), '2026-12-01').reason, 'Ended 2026-11-30')
  assert.equal(servingState(campaign({ endDate: null }), '2030-01-01').serving, true)
})

test('the ad server catalog splits the daily budget across pacing periods', () => {
  const cat = buildCatalog([campaign(), campaign({ id: 'ffffffffff', status: 'draft' })], '2026-10-25', 30)
  assert.equal(cat.campaigns.length, 1)
  const c = cat.campaigns[0]!
  assert.equal(c.campaignId, 'sp-a1b2c3d4e5')
  assert.equal(c.advertiserId, 'spon-the-one-dollar-world')
  const li = c.lineItems[0]!
  assert.equal(li.tier, 'GUARANTEED')
  assert.equal(li.cpmMicros, 20_000_000)
  assert.equal(li.periodBudgetMicros, 500_000, '$24 a day over 48 half-hours')
  assert.deepEqual(li.countries, ['NG', 'US'])
  assert.deepEqual(li.creatives[0], { adId: 'ad-cr-a1b2c3d4e5-ff00aa', creativeId: 'cr-a1b2c3d4e5-ff00aa', durationSeconds: 30 })
})

test('the public manifest lists only live creatives and nothing commercial', () => {
  const m = buildManifest([campaign(), campaign({ id: 'bbbbbbbbbb', status: 'paused', creative: { ...campaign().creative!, id: 'cr-x' } })], '2026-10-25')
  assert.deepEqual(Object.keys(m.creatives), ['cr-a1b2c3d4e5-ff00aa'])
  const c = m.creatives['cr-a1b2c3d4e5-ff00aa']!
  assert.equal(c.src, '/media/cr-a1b2c3d4e5-ff00aa.mp4')
  assert.equal(c.sponsor, 'The One Dollar World')
  assert.ok(!('cpm' in c) && !('dailyBudget' in c))
  assert.equal(sponsorSlug('  Café & Co!! '), 'cafe-co')
})

function box(type: string, body: Buffer): Buffer {
  const head = Buffer.alloc(8)
  head.writeUInt32BE(8 + body.length, 0)
  head.write(type, 4, 'latin1')
  return Buffer.concat([head, body])
}

function mvhd(timescale: number, duration: number): Buffer {
  const body = Buffer.alloc(100)
  body.writeUInt8(0, 0) // version 0
  body.writeUInt32BE(timescale, 12)
  body.writeUInt32BE(duration, 16)
  return box('mvhd', body)
}

const reader = (buf: Buffer) => async (offset: number, length: number) => buf.subarray(offset, offset + length)

test('MP4 inspection reads the length and whether it starts fast', async () => {
  const ftyp = box('ftyp', Buffer.from('isom\0\0\0\0isomavc1', 'latin1'))
  const moov = box('moov', mvhd(1000, 29_600))
  const mdat = box('mdat', Buffer.alloc(64))
  const fast = Buffer.concat([ftyp, moov, mdat])
  assert.deepEqual(await inspectMp4(reader(fast), fast.length), { durationSeconds: 30, fastStart: true })
  const slow = Buffer.concat([ftyp, mdat, moov])
  assert.deepEqual(await inspectMp4(reader(slow), slow.length), { durationSeconds: 30, fastStart: false })
  await assert.rejects(inspectMp4(reader(Buffer.from('not a video at all')), 18), /Not an MP4/)
  const noMoov = Buffer.concat([ftyp, mdat])
  await assert.rejects(inspectMp4(reader(noMoov), noMoov.length), /movie header/)
})
