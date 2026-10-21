<script setup lang="ts">
// Sponsor campaigns shown on the publisher site (1world1dollar.com) through the sponsor tag:
// create, upload the video, approve to go live, pause, and see delivery.
const session = useSession()

interface Creative { id: string; file: string; durationSeconds: number; bytes: number; fastStart: boolean; uploadedAt: string }
interface Campaign {
  id: string
  campaignId: string
  sponsor: string
  name: string
  clickUrl: string
  cpm: number
  dailyBudget: number
  countries: string[]
  startDate: string
  endDate: string | null
  status: 'draft' | 'approved' | 'paused'
  creative: Creative | null
  serving: boolean
  servingReason: string
  approvedAt: string | null
  approvedBy: string | null
}
interface ListResponse { campaigns: Campaign[]; countries: Record<string, string>; limits: { floorCpm: number; maxCpm: number; maxDailyBudget: number }; today: string }
interface Delivery { campaignId: string; impressions: number; completes: number; clicks: number; spend: number }

const data = ref<ListResponse | null>(null)
const delivery = ref<Record<string, Delivery>>({})
const deliveryAvailable = ref(true)
const error = ref('')
const notice = ref('')
const busy = ref('')
const editing = ref<Partial<Campaign> | null>(null)
const formError = ref('')
const uploadPct = ref<Record<string, number>>({})

const SITE = 'https://1world1dollar.com'
const PAGES = 'Homepage, News, Impact and Press'
const snippet = computed(() =>
  `<script async src="${typeof location === 'undefined' ? 'https://prime-tech.app' : location.origin}/tag/sponsor-v1.js"><\/script>\n<div data-pt-sponsor="home"></div>`,
)

async function load() {
  try {
    data.value = await $fetch<ListResponse>('/api/sponsors', { timeout: 8000 })
    error.value = ''
  } catch (err) {
    session.noteError(err)
    error.value = errorText(err)
  }
  try {
    const d = await $fetch<{ available: boolean; rows: Delivery[] }>('/api/sponsors/delivery', { timeout: 8000 })
    deliveryAvailable.value = d.available
    delivery.value = Object.fromEntries(d.rows.map((r) => [r.campaignId, r]))
  } catch {
    deliveryAvailable.value = false
  }
}
onMounted(load)
useVisibleInterval(load, 60_000)

function newCampaign() {
  formError.value = ''
  editing.value = {
    sponsor: '',
    name: '',
    clickUrl: SITE,
    cpm: 20,
    dailyBudget: 10,
    countries: [],
    startDate: data.value?.today ?? new Date().toISOString().slice(0, 10),
    endDate: null,
  }
}

function edit(c: Campaign) {
  formError.value = ''
  editing.value = { ...c, countries: [...c.countries] }
}

async function save() {
  if (!editing.value) return
  formError.value = ''
  busy.value = 'save'
  try {
    const wasApproved = editing.value.status === 'approved'
    const saved = await $fetch<Campaign>('/api/sponsors/save', { method: 'POST', body: editing.value })
    notice.value = wasApproved && saved.status === 'draft'
      ? `Saved. "${saved.name}" changed in a way that needs approving again, so it is off until you approve it.`
      : `Saved "${saved.name}".${saved.creative ? '' : ' Next: upload its video.'}`
    editing.value = null
    await load()
  } catch (err) {
    formError.value = errorText(err)
  } finally {
    busy.value = ''
  }
}

function upload(c: Campaign, file: File | undefined) {
  if (!file) return
  error.value = ''
  if (file.type && file.type !== 'video/mp4') {
    error.value = 'Choose an MP4 video file.'
    return
  }
  // XMLHttpRequest for upload progress; the server streams the body to disk.
  const xhr = new XMLHttpRequest()
  xhr.open('POST', `/api/sponsors/upload?id=${encodeURIComponent(c.id)}`)
  xhr.setRequestHeader('Content-Type', 'video/mp4')
  xhr.upload.onprogress = (e) => {
    if (e.lengthComputable) uploadPct.value = { ...uploadPct.value, [c.id]: Math.round((e.loaded / e.total) * 100) }
  }
  xhr.onloadend = async () => {
    const { [c.id]: _, ...rest } = uploadPct.value
    uploadPct.value = rest
    if (xhr.status >= 200 && xhr.status < 300) {
      notice.value = `Video uploaded for "${c.name}". Review it, then approve to put it live.`
    } else {
      let msg = `Upload failed (${xhr.status || 'network'})`
      try {
        msg = JSON.parse(xhr.responseText).statusMessage || msg
      } catch {}
      error.value = msg
    }
    await load()
  }
  uploadPct.value = { ...uploadPct.value, [c.id]: 0 }
  xhr.send(file)
}

async function setStatus(c: Campaign, status: 'approved' | 'paused') {
  if (status === 'approved') {
    const where = c.countries.length ? c.countries.map((k) => data.value?.countries[k] ?? k).join(', ') : 'every country'
    const ok = confirm(
      `Approve "${c.name}" for ${c.sponsor}?\n\n` +
        `• Shows as a "Sponsored" video on ${SITE} (${PAGES})\n` +
        `• $${c.cpm} per 1,000 views, up to $${c.dailyBudget} a day\n` +
        `• Viewers in ${where}\n` +
        `• From ${c.startDate}${c.endDate ? ` to ${c.endDate}` : ', no end date'}\n` +
        `• "Learn more" opens ${c.clickUrl}`,
    )
    if (!ok) return
  }
  busy.value = c.id
  error.value = ''
  try {
    await $fetch('/api/sponsors/status', { method: 'POST', body: { id: c.id, status } })
    notice.value = status === 'approved' ? `"${c.name}" approved. It goes live within a minute.` : `"${c.name}" paused.`
    await load()
  } catch (err) {
    error.value = errorText(err)
  } finally {
    busy.value = ''
  }
}

async function remove(c: Campaign) {
  if (!confirm(`Delete "${c.name}" and its video? Delivery history stays in the reports.`)) return
  busy.value = c.id
  try {
    await $fetch('/api/sponsors/delete', { method: 'POST', body: { id: c.id } })
    await load()
  } catch (err) {
    error.value = errorText(err)
  } finally {
    busy.value = ''
  }
}

async function copySnippet() {
  try {
    await navigator.clipboard.writeText(snippet.value)
    notice.value = 'Snippet copied.'
  } catch {}
}

const money = (n: number) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: n < 100 ? 2 : 0 }).format(n)
const mb = (b: number) => `${(b / 1024 / 1024).toFixed(1)} MB`
function stats(c: Campaign) {
  const d = delivery.value[c.campaignId]
  if (!d) return null
  return {
    ...d,
    completionRate: d.impressions ? d.completes / d.impressions : 0,
    ctr: d.impressions ? d.clicks / d.impressions : 0,
  }
}
</script>

<template>
  <div class="sponsors">
    <section class="card intro">
      <h2>Sponsors on 1world1dollar.com</h2>
      <p>
        Approved sponsors appear as a muted, clearly labelled <strong>Sponsored</strong> video card on the {{ PAGES }}
        pages of <a :href="SITE" target="_blank" rel="noopener">1world1dollar.com</a>, never on Donate, checkout or
        thank-you pages. The card sets no cookies and collects no device IDs; it counts anonymous plays, completions and
        clicks. Every new campaign, new video, or change to price, budget, link or countries waits for your approval.
      </p>
      <details>
        <summary class="muted">Tag for other pages or sites</summary>
        <pre><code>{{ snippet }}</code></pre>
        <button type="button" class="ghost small" @click="copySnippet">Copy</button>
      </details>
      <div class="actions">
        <button type="button" class="primary" @click="newCampaign">New sponsor campaign</button>
      </div>
    </section>

    <p v-if="notice" class="card banner" role="status">{{ notice }} <button type="button" class="ghost small" @click="notice = ''">Dismiss</button></p>
    <p v-if="error" class="card banner err" role="alert">▲ {{ error }}</p>

    <form v-if="editing" class="card form" @submit.prevent="save">
      <h2>{{ editing.id ? 'Edit campaign' : 'New sponsor campaign' }}</h2>
      <div class="grid">
        <label><span class="label">Sponsor</span><input v-model="editing.sponsor" type="text" maxlength="60" required placeholder="Business name shown on the card"></label>
        <label><span class="label">Campaign name</span><input v-model="editing.name" type="text" maxlength="100" required placeholder="e.g. Launch promo"></label>
        <label class="wide"><span class="label">"Learn more" link</span><input v-model="editing.clickUrl" type="url" required></label>
        <label>
          <span class="label">Price per 1,000 views (USD)</span>
          <input v-model.number="editing.cpm" type="number" :min="data?.limits.floorCpm ?? 5" :max="data?.limits.maxCpm ?? 200" step="0.5" required>
          <span class="hint muted">At least ${{ data?.limits.floorCpm ?? 5 }}. What the sponsor pays you per 1,000 views.</span>
        </label>
        <label>
          <span class="label">Daily budget (USD)</span>
          <input v-model.number="editing.dailyBudget" type="number" min="1" :max="data?.limits.maxDailyBudget ?? 1000" step="1" required>
          <span class="hint muted">Delivery stops for the day when this is reached.</span>
        </label>
        <label><span class="label">Start date</span><input v-model="editing.startDate" type="date" required></label>
        <label><span class="label">End date (optional)</span><input v-model="editing.endDate" type="date"></label>
      </div>
      <fieldset>
        <legend class="label">Countries (none ticked = everywhere)</legend>
        <label v-for="(name, code) in data?.countries ?? {}" :key="code" class="check">
          <input v-model="editing.countries" type="checkbox" :value="code"> {{ name }}
        </label>
      </fieldset>
      <p v-if="formError" class="err" role="alert">▲ {{ formError }}</p>
      <div class="actions">
        <button type="button" class="ghost" @click="editing = null">Cancel</button>
        <button type="submit" class="primary" :disabled="busy === 'save'">{{ busy === 'save' ? 'Saving…' : 'Save as draft' }}</button>
      </div>
    </form>

    <p v-if="data && data.campaigns.length === 0 && !editing" class="card muted empty">
      No sponsor campaigns yet. Start with the 1world1dollar launch promo: press <strong>New sponsor campaign</strong>,
      then upload the video.
    </p>

    <article v-for="c in data?.campaigns ?? []" :key="c.id" class="card campaign">
      <header>
        <div>
          <h2>{{ c.name }}</h2>
          <div class="muted">{{ c.sponsor }} · {{ money(c.cpm) }} per 1,000 views · up to {{ money(c.dailyBudget) }}/day</div>
        </div>
        <span class="pill" :class="{ live: c.serving, wait: !c.serving && c.status !== 'paused' }">{{ c.servingReason }}</span>
      </header>

      <div class="body">
        <div class="media">
          <video v-if="c.creative" :src="`/media/${c.creative.file}`" controls muted playsinline preload="metadata" />
          <div v-else class="novideo muted">No video yet</div>
          <div v-if="c.creative" class="muted small">
            {{ c.creative.durationSeconds }}s · {{ mb(c.creative.bytes) }}
            <span v-if="!c.creative.fastStart" class="warn"> · tip: export with "fast start" so it begins playing sooner</span>
          </div>
          <label class="upload">
            {{ uploadPct[c.id] != null ? `Uploading ${uploadPct[c.id]}%…` : c.creative ? 'Replace video' : 'Upload video (MP4, up to 60s)' }}
            <input type="file" accept="video/mp4" class="visually-hidden" :disabled="uploadPct[c.id] != null" @change="upload(c, ($event.target as HTMLInputElement).files?.[0])">
          </label>
        </div>
        <dl class="facts">
          <div><dt>Runs</dt><dd>{{ c.startDate }} → {{ c.endDate ?? 'no end date' }}</dd></div>
          <div><dt>Countries</dt><dd>{{ c.countries.length ? c.countries.map((k) => data?.countries[k] ?? k).join(', ') : 'Everywhere' }}</dd></div>
          <div><dt>Link</dt><dd><a :href="c.clickUrl" target="_blank" rel="noopener">{{ c.clickUrl }}</a></dd></div>
          <div v-if="c.approvedAt"><dt>Approved</dt><dd>{{ new Date(c.approvedAt).toLocaleString('en-US') }} by {{ c.approvedBy }}</dd></div>
          <template v-if="stats(c)">
            <div><dt>Views (30 days)</dt><dd>{{ formatWhole(stats(c)!.impressions) }}</dd></div>
            <div><dt>Watched to the end</dt><dd>{{ formatPercent(stats(c)!.completionRate, 0) }}</dd></div>
            <div><dt>Clicks</dt><dd>{{ formatWhole(stats(c)!.clicks) }} ({{ formatPercent(stats(c)!.ctr, 1) }})</dd></div>
            <div><dt>Earned</dt><dd>{{ money(stats(c)!.spend) }}</dd></div>
          </template>
          <div v-else-if="c.serving && deliveryAvailable"><dt>Delivery</dt><dd class="muted">No views yet</dd></div>
        </dl>
      </div>

      <footer class="actions">
        <button v-if="c.status !== 'approved'" type="button" class="primary small" :disabled="!c.creative || busy === c.id" @click="setStatus(c, 'approved')">
          Approve &amp; go live
        </button>
        <button v-else type="button" class="ghost small" :disabled="busy === c.id" @click="setStatus(c, 'paused')">Pause</button>
        <button type="button" class="ghost small" @click="edit(c)">Edit</button>
        <button type="button" class="ghost small" :disabled="busy === c.id" @click="remove(c)">Delete</button>
      </footer>
    </article>
  </div>
</template>

<style scoped>
.sponsors {
  display: grid;
  gap: 16px;
}
.intro p {
  margin: 0 0 10px;
}
pre {
  margin: 8px 0;
  padding: 10px;
  border: 1px solid var(--border);
  border-radius: 4px;
  background: rgba(1, 6, 12, 0.85);
  overflow-x: auto;
}
.actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  align-items: center;
  margin-top: 8px;
}
.banner {
  margin: 0;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}
.err {
  color: var(--critical);
}
.empty {
  margin: 0;
}
.form {
  display: grid;
  gap: 14px;
}
.form h2 {
  margin: 0;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 12px 16px;
}
.grid label {
  display: grid;
  gap: 4px;
}
.grid .wide {
  grid-column: 1 / -1;
}
.label {
  font-size: 12px;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--ink-2);
}
.hint {
  font-size: 12px;
}
input[type='date'] {
  font: inherit;
  padding: 8px 10px;
  border: 1px solid var(--axis);
  border-radius: 4px;
  background: rgba(1, 6, 12, 0.85);
  color: var(--ink-1);
  color-scheme: dark;
}
fieldset {
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 10px 12px;
  margin: 0;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 6px 12px;
}
.check {
  display: flex;
  gap: 8px;
  align-items: center;
}
.campaign header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
  flex-wrap: wrap;
}
.campaign h2 {
  margin: 0 0 4px;
}
.pill {
  font: 11px var(--mono);
  padding: 3px 10px;
  border-radius: 99px;
  border: 1px solid var(--border);
  color: var(--ink-muted);
  white-space: nowrap;
}
.pill.live {
  color: var(--good);
  border-color: rgba(61, 255, 162, 0.5);
  box-shadow: 0 0 8px rgba(61, 255, 162, 0.25);
}
.pill.wait {
  color: var(--warning);
  border-color: rgba(255, 194, 71, 0.5);
}
.body {
  display: grid;
  grid-template-columns: minmax(220px, 360px) 1fr;
  gap: 16px;
  margin: 14px 0 6px;
}
@media (max-width: 640px) {
  .body {
    grid-template-columns: 1fr;
  }
}
.media {
  display: grid;
  gap: 6px;
  align-content: start;
}
.media video,
.novideo {
  width: 100%;
  aspect-ratio: 16 / 9;
  border-radius: 6px;
  border: 1px solid var(--border);
  background: #000;
}
.novideo {
  display: grid;
  place-items: center;
}
.upload {
  justify-self: start;
  display: inline-flex;
  align-items: center;
  min-height: 30px;
  padding: 4px 12px;
  font-size: 12px;
  border: 1px solid var(--axis);
  border-radius: 4px;
  background: rgba(0, 229, 255, 0.06);
  cursor: pointer;
}
.upload:hover,
.upload:focus-within {
  border-color: var(--series-1);
  box-shadow: var(--glow);
}
.warn {
  color: var(--warning);
}
.small {
  font-size: 12px;
}
.facts {
  margin: 0;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
  gap: 10px 16px;
  align-content: start;
}
.facts dt {
  font-size: 11px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--ink-muted);
}
.facts dd {
  margin: 2px 0 0;
  overflow-wrap: anywhere;
}
footer.actions {
  border-top: 1px solid var(--grid);
  padding-top: 12px;
}
</style>
