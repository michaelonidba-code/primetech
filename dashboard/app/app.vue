<script setup lang="ts">
const session = useSession()
const online = useOnline()
const { canPrompt, iosHint, install } = useInstall()
const showIosHint = ref(false)

onMounted(() => session.check())
// Back online, or back from the background: the session may have expired meanwhile.
watch(online, (now) => now && session.check())
</script>

<template>
  <main>
    <header>
      <div>
        <h1>Ads Eventing</h1>
        <p class="muted">Live pipeline · ad server → player → gateway → Kafka → Flink → Druid</p>
      </div>
      <div class="actions">
        <button v-if="canPrompt" type="button" class="ghost" @click="install">Install app</button>
        <button v-else-if="iosHint" type="button" class="ghost" :aria-expanded="showIosHint"
          @click="showIosHint = !showIosHint">Install app</button>
        <template v-if="session.status.value === 'signed-in'">
          <span class="muted">{{ session.user.value }}</span>
          <button type="button" class="ghost" @click="session.signOut()">Sign out</button>
        </template>
        <span v-else-if="session.status.value === 'open'" class="muted" title="NUXT_AUTH_DISABLED=true">
          Sign-in off (local)
        </span>
      </div>
    </header>

    <p v-if="showIosHint" class="card hint">
      In Safari, tap <strong>Share</strong>, then <strong>Add to Home Screen</strong>.
    </p>

    <p v-if="!online" class="card banner" role="status">
      <span aria-hidden="true">▲</span> You're offline. Figures resume when the connection returns.
    </p>

    <p v-if="session.status.value === 'checking'" class="muted">Loading…</p>
    <LoginForm v-else-if="session.status.value === 'signed-out'" />
    <section v-else-if="session.status.value === 'misconfigured'" class="card">
      <h2>Sign-in is not configured</h2>
      <p class="muted">
        The server requires sign-in but has no valid users or session secret. Set
        <code>NUXT_AUTH_USERS</code> and <code>NUXT_SESSION_SECRET</code> (see the README, "Hosting").
      </p>
    </section>
    <DashboardView v-else />
  </main>
</template>

<style scoped>
main {
  max-width: 1200px;
  margin: 0 auto;
  /* Safe-area insets keep content clear of notches when installed as an app. */
  padding: max(24px, env(safe-area-inset-top)) max(16px, env(safe-area-inset-right))
    max(48px, env(safe-area-inset-bottom)) max(16px, env(safe-area-inset-left));
  display: grid;
  gap: 16px;
}
header {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: flex-end;
  gap: 12px;
}
h1 {
  margin: 0;
  font-size: 22px;
}
header p {
  margin: 2px 0 0;
}
.actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 13px;
}
.ghost {
  font: inherit;
  min-height: 36px;
  padding: 6px 12px;
  border: 1px solid var(--axis);
  border-radius: 8px;
  background: var(--surface);
  color: var(--ink-1);
  cursor: pointer;
}
.ghost:focus-visible {
  outline: 2px solid var(--series-1);
  outline-offset: 2px;
}
.hint,
.banner {
  margin: 0;
}
.banner span {
  color: var(--warning);
}
</style>
