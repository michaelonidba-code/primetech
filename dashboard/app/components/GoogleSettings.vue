<script setup lang="ts">
// Google connections for the YouTube growth console. Values are saved on the server; secrets come
// back masked and an empty field keeps the saved value.
const props = defineProps<{ open?: boolean }>()
const g = useGrowth()
const s = computed(() => g.status.value)

const form = reactive<Record<string, string>>({
  youtubeApiKey: '',
  channelHandle: '',
  oauthClientId: '',
  oauthClientSecret: '',
  adsDeveloperToken: '',
  adsCustomerId: '',
  adsLoginCustomerId: '',
})
const saving = ref(false)
const message = ref('')
const error = ref('')

const fields: { key: string; label: string; secret?: boolean; hint: string; placeholder?: string }[] = [
  { key: 'youtubeApiKey', label: 'YouTube Data API key', secret: true, hint: 'Google Cloud Console → APIs & Services → enable "YouTube Data API v3" → Credentials → Create API key.' },
  { key: 'channelHandle', label: 'Channel handle', hint: 'The channel to track.', placeholder: '@aeshamikaelsonTV' },
  { key: 'oauthClientId', label: 'OAuth client ID', hint: 'Credentials → Create OAuth client ID → Web application, with the redirect URI below.' },
  { key: 'oauthClientSecret', label: 'OAuth client secret', secret: true, hint: 'Shown when you create the OAuth client.' },
  { key: 'adsDeveloperToken', label: 'Google Ads developer token', secret: true, hint: 'Google Ads → Tools → API Center. Apply for Explorer access to run real campaigns.' },
  { key: 'adsCustomerId', label: 'Google Ads customer ID', hint: 'The account that pays for the ads, e.g. 123-456-7890 (top right in Google Ads).' },
  { key: 'adsLoginCustomerId', label: 'Manager account ID (optional)', hint: 'Only if you reach the ads account through a manager (MCC) account.' },
]

function placeholderFor(f: { key: string; placeholder?: string }) {
  const saved = s.value?.settings[f.key]
  if (saved) return `Saved: ${saved}`
  return f.placeholder ?? ''
}

async function save() {
  error.value = ''
  message.value = ''
  const patch: Record<string, string> = {}
  for (const [k, v] of Object.entries(form)) if (v.trim()) patch[k] = v.trim()
  if (Object.keys(patch).length === 0) {
    error.value = 'Nothing to save: type into at least one field.'
    return
  }
  saving.value = true
  try {
    await g.saveSettings(patch)
    for (const k of Object.keys(form)) form[k] = ''
    message.value = 'Saved.'
  } catch (err) {
    error.value = errorText(err)
  } finally {
    saving.value = false
  }
}

async function clearField(key: string) {
  error.value = ''
  try {
    await g.saveSettings({ [key]: null })
  } catch (err) {
    error.value = errorText(err)
  }
}

async function disconnect() {
  if (!confirm('Disconnect Google? Ad reporting and launching stop until you connect again.')) return
  try {
    await g.disconnect()
  } catch (err) {
    error.value = errorText(err)
  }
}

async function copyRedirect() {
  try {
    await navigator.clipboard.writeText(s.value?.redirectUri ?? '')
    message.value = 'Redirect URI copied.'
  } catch {
    message.value = ''
  }
}
</script>

<template>
  <details class="card settings" :open="props.open">
    <summary>
      <h2>Google connections</h2>
      <span class="badges">
        <span class="badge" :class="{ on: s?.youtubeReady }">YouTube {{ s?.youtubeReady ? 'on' : 'off' }}</span>
        <span class="badge" :class="{ on: s?.connected }">Google {{ s?.connected ? 'connected' : 'not connected' }}</span>
        <span class="badge" :class="{ on: s?.adsReady }">Ads {{ s?.adsReady ? 'ready' : 'off' }}</span>
      </span>
    </summary>

    <ol class="steps muted">
      <li>In <a href="https://console.cloud.google.com/apis/library" target="_blank" rel="noopener">Google Cloud Console</a>, create a project and enable <strong>YouTube Data API v3</strong> and <strong>Google Ads API</strong>.</li>
      <li>Create an API key and a <strong>Web application</strong> OAuth client. Add this exact redirect URI to it:
        <span class="redirect"><code>{{ s?.redirectUri }}</code> <button type="button" class="ghost small" @click="copyRedirect">Copy</button></span>
      </li>
      <li>In <a href="https://ads.google.com/aw/apicenter" target="_blank" rel="noopener">Google Ads → API Center</a>, get your developer token and apply for Explorer or Basic access.</li>
      <li>Paste the values below, save, then press <strong>Connect Google</strong> and approve with the Google account that owns the ads account.</li>
    </ol>

    <form class="grid" @submit.prevent="save">
      <label v-for="f in fields" :key="f.key">
        <span class="label">{{ f.label }}</span>
        <span class="row">
          <input
            v-model="form[f.key]"
            :type="f.secret ? 'password' : 'text'"
            :name="f.key"
            :placeholder="placeholderFor(f)"
            autocomplete="off"
            spellcheck="false"
          >
          <button v-if="s?.settings[f.key]" type="button" class="ghost small" :aria-label="`Clear ${f.label}`" @click="clearField(f.key)">Clear</button>
        </span>
        <span class="hint muted">{{ f.hint }}</span>
      </label>
      <div class="actions">
        <button type="submit" class="primary" :disabled="saving">{{ saving ? 'Saving…' : 'Save' }}</button>
        <a v-if="s?.oauthReady" class="button primary" href="/api/google/connect">{{ s?.connected ? 'Reconnect Google' : 'Connect Google' }}</a>
        <button v-if="s?.connected" type="button" class="ghost" @click="disconnect">Disconnect</button>
        <span v-if="s?.connectedEmail" class="muted">Connected as {{ s.connectedEmail }}</span>
      </div>
      <p v-if="message" class="ok" role="status">{{ message }}</p>
      <p v-if="error" class="error" role="alert"><span aria-hidden="true">▲</span> {{ error }}</p>
      <p class="muted small">Google Ads API {{ s?.apiVersion }} · keys are stored on the server only and never shown again in full.</p>
    </form>
  </details>
</template>

<style scoped>
summary {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  cursor: pointer;
  list-style: none;
}
summary::-webkit-details-marker {
  display: none;
}
summary h2 {
  margin: 0;
}
summary h2::before {
  content: '▸ ';
}
details[open] summary h2::before {
  content: '▾ ';
}
.badges {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.badge {
  font: 11px var(--mono);
  padding: 2px 8px;
  border: 1px solid var(--border);
  border-radius: 99px;
  color: var(--ink-muted);
}
.badge.on {
  color: var(--good);
  border-color: rgba(61, 255, 162, 0.45);
  box-shadow: 0 0 8px rgba(61, 255, 162, 0.2);
}
.steps {
  margin: 16px 0;
  padding-left: 20px;
  display: grid;
  gap: 6px;
}
.redirect {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 4px;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 14px 18px;
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
.row {
  display: flex;
  gap: 6px;
}
.row input {
  flex: 1;
  min-width: 0;
}
.hint {
  font-size: 12px;
}
.actions {
  grid-column: 1 / -1;
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
}
.ok {
  margin: 0;
  color: var(--good);
}
.error {
  margin: 0;
  color: var(--critical);
}
p.small,
.small {
  font-size: 12px;
}
</style>
