<script setup lang="ts">
const session = useSession()
const username = ref('')
const password = ref('')
const error = ref('')
const busy = ref(false)

async function submit() {
  error.value = ''
  busy.value = true
  try {
    await session.signIn(username.value.trim(), password.value)
    password.value = ''
  } catch (err) {
    const code = session.statusOf(err)
    error.value =
      code === 401 ? 'Wrong user name or password.'
      : code === 429 ? 'Too many failed attempts. Try again in 15 minutes.'
      : code === 503 ? 'Sign-in is not configured on the server.'
      : 'Could not reach the server. Check your connection.'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="login-wrap">
    <CircuitPipeline class="circuit" />
    <section class="card login" aria-labelledby="login-title">
      <BrandLogo class="logo" :height="80" />
      <h2 id="login-title">Sign in</h2>
      <p class="muted">The dashboard shows live delivery and spend data. Sign in to continue.</p>
      <form @submit.prevent="submit">
        <label>
          User name
          <input v-model="username" name="username" autocomplete="username" autocapitalize="none"
            spellcheck="false" required maxlength="64">
        </label>
        <label>
          Password
          <input v-model="password" name="password" type="password" autocomplete="current-password" required>
        </label>
        <p v-if="error" class="error" role="alert"><span aria-hidden="true">▲</span> {{ error }}</p>
        <button type="submit" :disabled="busy">{{ busy ? 'Signing in…' : 'Sign in' }}</button>
      </form>
    </section>
  </div>
</template>

<style scoped>
.login-wrap {
  display: grid;
  gap: 28px;
  margin-top: 6vh;
}
.login {
  max-width: 400px;
  width: 100%;
  margin: 0 auto;
  padding: 24px;
  box-shadow: inset 0 0 0 1px rgba(0, 229, 255, 0.05), 0 0 40px rgba(0, 229, 255, 0.12);
}
.logo {
  display: block;
  margin: 4px auto 18px;
  background: var(--series-1);
  filter: drop-shadow(0 0 8px rgba(0, 229, 255, 0.75));
}
.login h2 {
  text-align: center;
  font-size: 15px;
}
.login > p {
  margin: 0 0 20px;
  text-align: center;
}
form {
  display: grid;
  gap: 14px;
}
label {
  display: grid;
  gap: 6px;
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--ink-2);
}
input {
  font: inherit;
  /* 16px stops iOS Safari zooming into the field. */
  font-size: 16px;
  letter-spacing: normal;
  text-transform: none;
  padding: 11px 12px;
  border: 1px solid var(--axis);
  border-radius: 4px;
  background: rgba(1, 6, 12, 0.85);
  color: var(--ink-1);
  transition: border-color 0.2s, box-shadow 0.2s;
}
input:focus {
  border-color: var(--series-1);
  box-shadow: var(--glow);
}
input:focus-visible,
button:focus-visible {
  outline: 2px solid var(--series-1);
  outline-offset: 2px;
}
button {
  font-family: var(--display);
  font-size: 14px;
  font-weight: 800;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  padding: 12px;
  min-height: 46px;
  margin-top: 4px;
  border: 0;
  border-radius: 4px;
  background: var(--series-1);
  color: var(--on-accent);
  box-shadow: var(--glow-strong);
  cursor: pointer;
  transition: filter 0.2s, box-shadow 0.2s;
}
button:hover:not(:disabled) {
  filter: brightness(1.15);
  box-shadow: 0 0 8px rgba(0, 229, 255, 1), 0 0 32px rgba(0, 229, 255, 0.6);
}
button:disabled {
  opacity: 0.6;
  cursor: progress;
}
.error {
  margin: 0;
  color: var(--critical);
  font-size: 13px;
  text-shadow: 0 0 8px rgba(255, 77, 109, 0.5);
}
</style>
