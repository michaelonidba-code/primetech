import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  campaignRows,
  cleanCustomerId,
  demandGenOperations,
  googleErrorMessage,
  LIMITS,
  maskSecret,
  normalizeHandle,
  validateLaunch,
} from '../server/utils/google-ads.ts'

const good = {
  videoId: 'dQw4w9WgXcQ',
  name: 'Launch test',
  dailyBudget: 10,
  countries: ['NG', 'GH', 'NG'],
  headline: 'A new dark fantasy short',
  longHeadline: 'Stories are not told here. They are forged.',
  description: 'Watch the latest original short film.',
  businessName: 'Aesha Mikaelson TV',
  finalUrl: 'https://www.youtube.com/@aeshamikaelsonTV',
}

test('a complete launch request validates and is cleaned', () => {
  const { value, errors } = validateLaunch(good)
  assert.deepEqual(errors, [])
  assert.deepEqual(value?.countries, ['NG', 'GH'], 'duplicates dropped')
})

test('launch validation rejects bad input with readable reasons', () => {
  const { value, errors } = validateLaunch({
    ...good,
    videoId: 'nope',
    dailyBudget: 0,
    countries: ['XX'],
    headline: 'x'.repeat(LIMITS.headline + 1),
    finalUrl: 'http://example.com',
  })
  assert.equal(value, undefined)
  assert.ok(errors.some((e) => e.includes('video')))
  assert.ok(errors.some((e) => e.includes('Daily budget')))
  assert.ok(errors.some((e) => e.includes('country')))
  assert.ok(errors.some((e) => e.startsWith('Headline: at most 40')))
  assert.ok(errors.some((e) => e.includes('https://')))
  assert.ok(validateLaunch({ ...good, dailyBudget: 5000 }, 1000).errors.some((e) => e.includes('capped')))
  assert.ok(validateLaunch(null).errors.length > 3)
})

test('campaigns are created paused, YouTube only, with the video and logo linked', () => {
  const { value } = validateLaunch(good)
  const ops = demandGenOperations({
    customerId: '1234567890',
    input: value!,
    geoTargetConstants: ['geoTargetConstants/2566', 'geoTargetConstants/2288'],
    logoImageBase64: 'AAAA',
    stamp: '20261021T0500',
  }) as any[]
  const campaign = ops.find((o) => o.campaignOperation).campaignOperation.create
  assert.equal(campaign.status, 'PAUSED')
  assert.equal(campaign.advertisingChannelType, 'DEMAND_GEN')
  assert.equal(campaign.campaignBudget, 'customers/1234567890/campaignBudgets/-1')
  const budget = ops[0].campaignBudgetOperation.create
  assert.equal(budget.amountMicros, '10000000')
  assert.equal(budget.explicitlyShared, false)
  const channels = ops.find((o) => o.adGroupOperation).adGroupOperation.create.demandGenAdGroupSettings.channelControls.selectedChannels
  assert.deepEqual(
    Object.entries(channels).filter(([, on]) => on).map(([k]) => k).sort(),
    ['youtubeInFeed', 'youtubeInStream', 'youtubeShorts'],
  )
  assert.equal(ops.filter((o) => o.adGroupCriterionOperation).length, 2)
  const ad = ops.at(-1).adGroupAdOperation.create.ad.demandGenVideoResponsiveAd
  assert.equal(ad.videos[0].asset, 'customers/1234567890/assets/-4')
  assert.equal(ad.logoImages[0].asset, 'customers/1234567890/assets/-5')
  // Order matters for temporary IDs: everything is created before it is referenced.
  const order = ops.map((o) => Object.keys(o)[0])
  assert.ok(order.indexOf('campaignBudgetOperation') < order.indexOf('campaignOperation'))
  assert.ok(order.lastIndexOf('assetOperation') < order.indexOf('adGroupAdOperation'))
})

test('helpers: IDs, handles, masking, Google errors, report folding', () => {
  assert.equal(cleanCustomerId('123-456-7890'), '1234567890')
  assert.equal(normalizeHandle('https://www.youtube.com/@aeshamikaelsonTV'), '@aeshamikaelsonTV')
  assert.equal(normalizeHandle('aeshamikaelsonTV'), '@aeshamikaelsonTV')
  assert.equal(normalizeHandle('@a b'), '')
  assert.equal(maskSecret('AIzaSyExample1234'), '••••1234')
  assert.equal(maskSecret(''), '')
  assert.equal(
    googleErrorMessage({ error: { message: 'Request contains an invalid argument.', details: [{ errors: [{ message: 'Too long.' }] }] } }, 'x'),
    'Too long.',
  )
  assert.equal(googleErrorMessage({ error: 'invalid_grant', error_description: 'Bad Request' }, 'x'), 'invalid_grant: Bad Request')
  const rows = campaignRows(
    [
      { campaign: { id: '1', name: 'A', status: 'PAUSED', advertisingChannelType: 'DEMAND_GEN' }, metrics: {} },
      { campaign: { id: '2', name: 'B', status: 'ENABLED' }, metrics: { costMicros: '2000000', impressions: '10', videoTrueviewViews: '4' } },
      { campaign: { id: '2', name: 'B', status: 'ENABLED' }, metrics: { costMicros: '1000000', clicks: '1', videoTrueviewViews: '1' } },
    ],
    true,
  )
  assert.equal(rows[0]!.id, '2', 'highest spend first')
  assert.equal(rows[0]!.costMicros, 3_000_000)
  assert.equal(rows[0]!.videoViews, 5)
  assert.equal(rows[1]!.videoViews, 0)
})
