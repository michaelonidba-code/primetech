<script setup lang="ts">
// YouTube growth console: real channel statistics, progress to the goals, growth history,
// Google Ads (Demand Gen) campaigns, and promoting a video.
import type { Campaign, Video } from '~/composables/useGrowth'

const g = useGrowth()
const notice = ref('')
const promoting = ref<Video | null>(null)
const busyCampaign = ref('')
const campaignError = ref('')

const NOTICES: Record<string, string> = {
  connected: 'Google account connected.',
  denied: 'Google sign-in was cancelled.',
  expired: 'That Google sign-in link expired. Press Connect Google again.',
  failed: 'Google sign-in failed. Check the OAuth client ID, secret and redirect URI, then try again.',
}

onMounted(async () => {
  const q = new URLSearchParams(location.search).get('google')
  if (q) {
    notice.value = NOTICES[q] ?? ''
    window.history.replaceState(null, '', `${location.pathname}#youtube`)
  }
  await g.load()
})
useVisibleInterval(() => g.load(), 5 * 60_000)

const status = computed(() => g.status.value)
const ov = computed(() => g.overview.value)
const ads = computed(() => g.ads.value)
const ch = computed(() => ov.value?.channel)

function pct(value: number | null | undefined, goal: number) {
  return Math.min(100, ((value ?? 0) / goal) * 100)
}

/** Change over the last 7 days of recorded history, or null if there isn't a week yet. */
function weekChange(key: 'subscribers' | 'views') {
  const h = ov.value?.history ?? []
  if (h.length < 2) return null
  const cutoff = Date.now() - 7 * 86_400_000
  const start = h.find((p) => Date.parse(p.t) >= cutoff) ?? h[0]!
  const end = h.at(-1)!
  const a = start[key]
  const b = end[key]
  return a == null || b == null ? null : b - a
}

const subsSeries = computed(() => (ov.value?.history ?? []).filter((p) => p.subscribers != null).map((p) => ({ t: p.t, v: p.subscribers as number })))
const viewsSeries = computed(() => (ov.value?.history ?? []).map((p) => ({ t: p.t, v: p.views })))

const money = (n: number | null | undefined) => {
  const cur = ads.value?.account.currency || 'USD'
  try {
    return new Intl.NumberFormat('en-US', { style: 'currency', currency: cur, maximumFractionDigits: n != null && n < 100 ? 2 : 0 }).format(n ?? 0)
  } catch {
    return `${(n ?? 0).toFixed(2)} ${cur}`
  }
}

function perView(c: Campaign) {
  const v = c.videoViews ?? 0
  return v > 0 ? money(c.cost / v) : '–'
}

async function toggle(c: Campaign) {
  campaignError.value = ''
  const next = c.status === 'ENABLED' ? 'PAUSED' : 'ENABLED'
  if (next === 'ENABLED') {
    const budget = c.dailyBudget != null ? `up to ${money(c.dailyBudget)} a day` : 'its daily budget'
    if (!confirm(`Start "${c.name}"?\n\nGoogle will begin showing the ad and charging your Google Ads account ${budget}.`)) return
  }
  busyCampaign.value = c.id
  try {
    await g.setCampaignStatus(c.id, next)
  } catch (err) {
    campaignError.value = errorText(err)
  } finally {
    busyCampaign.value = ''
  }
}

function launched() {
  notice.value = 'Campaign created and paused. Review it below, then press Start when you are ready to spend.'
}

const published = (iso: string) => (iso ? new Date(iso).toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' }) : '')
</script>

<template>
  <div class="growth">
    <p v-if="notice" class="card banner" role="status">
      {{ notice }} <button type="button" class="ghost small" @click="notice = ''">Dismiss</button>
    </p>
    <p v-if="g.errors.value.status" class="card banner error" role="alert">▲ {{ g.errors.value.status }}</p>

    <section v-if="status && !status.youtubeReady" class="card intro">
      <h2>Connect your channel</h2>
      <p>
        Add a YouTube Data API key in <strong>Google connections</strong> below to see live subscribers, views and
        videos for <strong>{{ status.channelHandle }}</strong>. Connect Google Ads to launch and track real YouTube ad
        campaigns from here.
      </p>
    </section>

    <template v-if="ch">
      <section class="card channel">
        <img v-if="ch.thumbnail" :src="ch.thumbnail" alt="" width="64" height="64" class="avatar">
        <div class="who">
          <h2>{{ ch.title }}</h2>
          <a :href="ch.url" target="_blank" rel="noopener" class="muted">{{ ch.handle }}</a>
        </div>
        <div class="refresh">
          <span class="muted small">Updated {{ new Date(ov!.fetchedAt).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' }) }}</span>
          <button type="button" class="ghost small" :disabled="g.loading.value" @click="g.load(true)">
            {{ g.loading.value ? 'Refreshing…' : 'Refresh' }}
          </button>
        </div>
      </section>

      <section class="goals">
        <div class="card goal">
          <div class="label">Subscribers</div>
          <div class="value">{{ ch.subscribers == null ? 'Hidden' : formatWhole(ch.subscribers) }}</div>
          <div class="bar" role="progressbar" :aria-valuenow="Math.round(pct(ch.subscribers, GOALS.subscribers))" aria-valuemin="0" aria-valuemax="100">
            <span :style="{ width: `${Math.max(pct(ch.subscribers, GOALS.subscribers), 0.6)}%` }" />
          </div>
          <div class="detail muted">
            {{ pct(ch.subscribers, GOALS.subscribers).toFixed(pct(ch.subscribers, GOALS.subscribers) < 1 ? 3 : 1) }}% of
            {{ formatCompact(GOALS.subscribers) }} goal
            <span v-if="weekChange('subscribers') != null"> · {{ weekChange('subscribers')! >= 0 ? '+' : '' }}{{ formatWhole(weekChange('subscribers')!) }} this week</span>
          </div>
        </div>
        <div class="card goal">
          <div class="label">Channel views</div>
          <div class="value">{{ formatWhole(ch.views) }}</div>
          <div class="bar" role="progressbar" :aria-valuenow="Math.round(pct(ch.views, GOALS.views))" aria-valuemin="0" aria-valuemax="100">
            <span class="pink" :style="{ width: `${Math.max(pct(ch.views, GOALS.views), 0.6)}%` }" />
          </div>
          <div class="detail muted">
            {{ pct(ch.views, GOALS.views).toFixed(pct(ch.views, GOALS.views) < 1 ? 3 : 1) }}% of {{ formatCompact(GOALS.views) }} goal
            <span v-if="weekChange('views') != null"> · {{ weekChange('views')! >= 0 ? '+' : '' }}{{ formatWhole(weekChange('views')!) }} this week</span>
          </div>
        </div>
        <StatTile label="Videos" :value="formatWhole(ch.videos)" :detail="ov!.videos[0] ? `Latest ${published(ov!.videos[0].publishedAt)}` : undefined" />
      </section>

      <section class="charts">
        <TrendChart title="Subscribers" :points="subsSeries" />
        <TrendChart title="Views" :points="viewsSeries" color="var(--accent-2)" />
      </section>
    </template>
    <p v-else-if="g.errors.value.overview" class="card banner error" role="alert">▲ YouTube: {{ g.errors.value.overview }}</p>

    <section v-if="status?.youtubeReady" class="card campaigns">
      <h2>YouTube ad campaigns <span v-if="ads" class="muted acct">{{ ads.account.name || ads.account.id }} · {{ ads.account.currency }} · last 30 days</span></h2>
      <p v-if="!status.adsReady" class="muted">
        Connect Google Ads in <strong>Google connections</strong> to launch and track campaigns here.
      </p>
      <p v-else-if="g.errors.value.ads" class="error" role="alert">▲ {{ g.errors.value.ads }}</p>
      <template v-else-if="ads">
        <p v-if="ads.campaigns.length === 0" class="muted">No campaigns yet. Press <strong>Promote</strong> on a video below to create one.</p>
        <div v-else class="scroll">
          <table class="data">
            <thead>
              <tr>
                <th scope="col">Campaign</th>
                <th scope="col">Status</th>
                <th scope="col" class="num">Daily budget</th>
                <th scope="col" class="num">Spent</th>
                <th scope="col" class="num">Impressions</th>
                <th scope="col" class="num">Views</th>
                <th scope="col" class="num">Per view</th>
                <th scope="col" class="num">Clicks</th>
                <th scope="col"><span class="visually-hidden">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="c in ads.campaigns" :key="c.id">
                <td>{{ c.name }}<div class="muted small">{{ c.type === 'DEMAND_GEN' ? 'Demand Gen' : 'Video' }} · {{ c.id }}</div></td>
                <td><span class="pill" :class="c.status.toLowerCase()">{{ c.status === 'ENABLED' ? 'Running' : c.status === 'PAUSED' ? 'Paused' : c.status }}</span></td>
                <td class="num">{{ c.dailyBudget != null ? money(c.dailyBudget) : '–' }}</td>
                <td class="num">{{ money(c.cost) }}</td>
                <td class="num">{{ formatWhole(c.impressions) }}</td>
                <td class="num">{{ c.videoViews == null ? '–' : formatWhole(c.videoViews) }}</td>
                <td class="num">{{ perView(c) }}</td>
                <td class="num">{{ formatWhole(c.clicks) }}</td>
                <td>
                  <button
                    v-if="c.type === 'DEMAND_GEN' && (c.status === 'ENABLED' || c.status === 'PAUSED')"
                    type="button"
                    :class="c.status === 'ENABLED' ? 'ghost small' : 'primary small'"
                    :disabled="busyCampaign === c.id"
                    @click="toggle(c)"
                  >
                    {{ c.status === 'ENABLED' ? 'Pause' : 'Start' }}
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-if="campaignError" class="error" role="alert">▲ {{ campaignError }}</p>
      </template>
      <p v-else class="muted">Loading campaigns…</p>
    </section>

    <section v-if="ov && ov.videos.length" class="card videos">
      <h2>Videos</h2>
      <ul>
        <li v-for="v in ov.videos" :key="v.id">
          <a :href="`https://www.youtube.com/watch?v=${v.id}`" target="_blank" rel="noopener" class="thumb" :aria-label="v.title">
            <img v-if="v.thumbnail" :src="v.thumbnail" alt="" width="160" height="90" loading="lazy">
            <span v-else class="noimg" />
          </a>
          <div class="meta">
            <a :href="`https://www.youtube.com/watch?v=${v.id}`" target="_blank" rel="noopener" class="title">{{ v.title }}</a>
            <div class="muted small">
              {{ published(v.publishedAt) }} · {{ formatWhole(v.views) }} views · {{ formatWhole(v.likes) }} likes · {{ formatWhole(v.comments) }} comments
            </div>
          </div>
          <button
            type="button"
            class="primary small"
            :disabled="!status?.adsReady"
            :title="status?.adsReady ? 'Create a YouTube ad campaign for this video' : 'Connect Google Ads first'"
            @click="promoting = v"
          >
            Promote
          </button>
        </li>
      </ul>
    </section>

    <GoogleSettings :open="!!status && (!status.youtubeReady || !status.adsReady)" />

    <LaunchCampaign
      v-if="promoting && ch"
      :video="promoting"
      :channel-title="ch.title"
      :currency="ads?.account.currency ?? ''"
      :max-daily-budget="status?.maxDailyBudget ?? 1000"
      @close="promoting = null"
      @launched="launched"
    />
  </div>
</template>

<style scoped>
.growth {
  display: grid;
  gap: 16px;
}
.banner {
  margin: 0;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}
.banner.error,
.error {
  color: var(--critical);
}
.intro p {
  margin: 0;
}
.channel {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}
.avatar {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  border: 2px solid var(--series-1);
  box-shadow: var(--glow-strong);
}
.who {
  flex: 1;
  min-width: 160px;
}
.who h2 {
  margin: 0 0 2px;
  font-size: 16px;
}
.refresh {
  display: flex;
  align-items: center;
  gap: 10px;
}
.goals {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px;
}
.goal {
  display: grid;
  gap: 6px;
}
.goal .label {
  color: var(--ink-2);
  font-size: 12px;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}
.goal .value {
  font-family: var(--display);
  font-size: clamp(22px, 6vw, 30px);
  font-weight: 600;
  color: #f2fdff;
  text-shadow: 0 0 6px rgba(0, 229, 255, 0.7), 0 0 18px rgba(0, 229, 255, 0.35);
}
.bar {
  height: 8px;
  border-radius: 99px;
  background: rgba(0, 229, 255, 0.08);
  border: 1px solid var(--border);
  overflow: hidden;
}
.bar span {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, rgba(0, 229, 255, 0.4), var(--series-1));
  box-shadow: var(--glow-strong);
}
.bar span.pink {
  background: linear-gradient(90deg, rgba(255, 47, 160, 0.4), var(--accent-2));
  box-shadow: 0 0 6px rgba(255, 47, 160, 0.9), 0 0 18px rgba(255, 47, 160, 0.4);
}
.detail {
  font-size: 12px;
}
.charts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: 12px;
}
.acct {
  font-family: var(--mono);
  text-transform: none;
  letter-spacing: 0;
  text-shadow: none;
  font-weight: 400;
  margin-left: 6px;
}
.scroll {
  overflow-x: auto;
}
.pill {
  font: 11px var(--mono);
  padding: 2px 8px;
  border-radius: 99px;
  border: 1px solid var(--border);
  white-space: nowrap;
}
.pill.enabled {
  color: var(--good);
  border-color: rgba(61, 255, 162, 0.5);
  box-shadow: 0 0 8px rgba(61, 255, 162, 0.25);
}
.pill.paused {
  color: var(--warning);
  border-color: rgba(255, 194, 71, 0.5);
}
.videos ul {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 10px;
}
.videos li {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--grid);
}
.videos li:last-child {
  border-bottom: 0;
  padding-bottom: 0;
}
.thumb img,
.noimg {
  display: block;
  width: 120px;
  height: 68px;
  object-fit: cover;
  border-radius: 4px;
  border: 1px solid var(--border);
}
.meta {
  flex: 1;
  min-width: 0;
}
.title {
  color: var(--ink-1);
  text-decoration: none;
  font-weight: 600;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.title:hover {
  color: var(--series-1);
}
.small {
  font-size: 12px;
}
@media (max-width: 560px) {
  .thumb img {
    width: 96px;
    height: 54px;
  }
  .videos li {
    flex-wrap: wrap;
  }
}
</style>
