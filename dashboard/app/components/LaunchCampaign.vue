<script setup lang="ts">
// Launch form for one video: creates a paused, YouTube-only Demand Gen campaign. Spending starts
// only when the campaign is started from the campaigns table.
import type { Video } from '~/composables/useGrowth'

const props = defineProps<{ video: Video; channelTitle: string; currency: string; maxDailyBudget: number }>()
const emit = defineEmits<{ close: []; launched: [campaignId: string] }>()
const g = useGrowth()

const COUNTRIES: Record<string, string> = {
  NG: 'Nigeria', GH: 'Ghana', BJ: 'Benin', TG: 'Togo', KE: 'Kenya', ZA: 'South Africa', US: 'United States',
  GB: 'United Kingdom', CA: 'Canada', IE: 'Ireland', AE: 'UAE', QA: 'Qatar', AU: 'Australia', DE: 'Germany', FR: 'France',
}
const LIMITS = { headline: 40, longHeadline: 90, description: 90, businessName: 25 }
const cut = (s: string, n: number) => (s.length <= n ? s : `${s.slice(0, n - 1).trimEnd()}…`)

const form = reactive({
  name: cut(`${props.video.title} – YouTube`, 120),
  dailyBudget: 10,
  countries: ['NG', 'GH', 'US', 'GB'] as string[],
  headline: cut(props.video.title, LIMITS.headline),
  longHeadline: cut(props.video.title, LIMITS.longHeadline),
  description: cut(`New on ${props.channelTitle}: watch the full film now.`, LIMITS.description),
  businessName: cut(props.channelTitle, LIMITS.businessName),
  finalUrl: `https://www.youtube.com/watch?v=${props.video.id}`,
})
const busy = ref(false)
const error = ref('')
const dialog = ref<HTMLDialogElement | null>(null)

onMounted(() => dialog.value?.showModal())

const monthly = computed(() => Math.round(form.dailyBudget * 30.4))

async function submit() {
  error.value = ''
  busy.value = true
  try {
    const res = await g.launch({ ...form, videoId: props.video.id })
    emit('launched', res.campaignId)
    dialog.value?.close()
  } catch (err) {
    error.value = errorText(err)
  } finally {
    busy.value = false
  }
}

function close() {
  dialog.value?.close()
}
</script>

<template>
  <dialog ref="dialog" class="launch" aria-labelledby="launch-title" @close="emit('close')">
    <form method="dialog" class="card" @submit.prevent="submit">
      <h2 id="launch-title">Promote on YouTube</h2>
      <div class="video">
        <img v-if="video.thumbnail" :src="video.thumbnail" alt="" width="160" height="90">
        <div>
          <strong>{{ video.title }}</strong>
          <p class="muted">{{ formatWhole(video.views) }} views so far</p>
        </div>
      </div>

      <label>
        <span class="label">Campaign name</span>
        <input v-model="form.name" type="text" maxlength="120" required>
      </label>
      <label>
        <span class="label">Daily budget ({{ currency || 'account currency' }})</span>
        <input v-model.number="form.dailyBudget" type="number" min="1" :max="maxDailyBudget" step="1" required>
        <span class="hint muted">Up to about {{ formatWhole(monthly) }} {{ currency }} a month if it runs every day.</span>
      </label>

      <fieldset>
        <legend class="label">Show it to people in</legend>
        <label v-for="(name, code) in COUNTRIES" :key="code" class="check">
          <input v-model="form.countries" type="checkbox" :value="code"> {{ name }}
        </label>
      </fieldset>

      <label>
        <span class="label">Headline <span class="count">{{ form.headline.length }}/{{ LIMITS.headline }}</span></span>
        <input v-model="form.headline" type="text" :maxlength="LIMITS.headline" required>
      </label>
      <label>
        <span class="label">Long headline <span class="count">{{ form.longHeadline.length }}/{{ LIMITS.longHeadline }}</span></span>
        <input v-model="form.longHeadline" type="text" :maxlength="LIMITS.longHeadline" required>
      </label>
      <label>
        <span class="label">Description <span class="count">{{ form.description.length }}/{{ LIMITS.description }}</span></span>
        <input v-model="form.description" type="text" :maxlength="LIMITS.description" required>
      </label>
      <label>
        <span class="label">Business name <span class="count">{{ form.businessName.length }}/{{ LIMITS.businessName }}</span></span>
        <input v-model="form.businessName" type="text" :maxlength="LIMITS.businessName" required>
      </label>
      <label>
        <span class="label">Link (final URL)</span>
        <input v-model="form.finalUrl" type="url" required>
      </label>

      <p class="note muted">
        Creates a <strong>paused</strong> Demand Gen campaign shown only on YouTube (in-stream, feed and Shorts), using
        the channel picture as the logo. Nothing is spent until you press <strong>Start</strong> in the campaigns table.
      </p>
      <p v-if="error" class="error" role="alert"><span aria-hidden="true">▲</span> {{ error }}</p>
      <div class="actions">
        <button type="button" class="ghost" @click="close">Cancel</button>
        <button type="submit" class="primary" :disabled="busy || form.countries.length === 0">
          {{ busy ? 'Creating…' : 'Create paused campaign' }}
        </button>
      </div>
    </form>
  </dialog>
</template>

<style scoped>
dialog {
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  width: min(640px, calc(100vw - 24px));
  max-height: calc(100dvh - 24px);
  overflow: auto;
}
dialog::backdrop {
  background: rgba(1, 4, 9, 0.75);
  backdrop-filter: blur(3px);
}
form {
  display: grid;
  gap: 14px;
  padding: 20px;
  background: var(--surface-solid);
}
h2 {
  margin: 0;
}
.video {
  display: flex;
  gap: 12px;
  align-items: center;
}
.video img {
  width: 128px;
  height: 72px;
  object-fit: cover;
  border-radius: 4px;
  border: 1px solid var(--border);
}
.video p {
  margin: 2px 0 0;
}
label {
  display: grid;
  gap: 4px;
}
.label {
  font-size: 12px;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--ink-2);
}
.count {
  float: right;
  font-family: var(--mono);
  text-transform: none;
  color: var(--ink-muted);
}
.hint {
  font-size: 12px;
}
fieldset {
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 10px 12px;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 6px 12px;
  margin: 0;
}
legend {
  padding: 0 6px;
}
.check {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
}
.note {
  margin: 0;
  font-size: 13px;
}
.error {
  margin: 0;
  color: var(--critical);
}
.actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
</style>
