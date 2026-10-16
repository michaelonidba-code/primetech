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
  <section class="card login" aria-labelledby="login-title">
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
</template>

<style scoped>
.login {
  max-width: 380px;
  width: 100%;
  margin: 8vh auto 0;
}
.login > p {
  margin: 0 0 16px;
}
form {
  display: grid;
  gap: 12px;
}
label {
  display: grid;
  gap: 4px;
  font-size: 13px;
  color: var(--ink-2);
}
input {
  font: inherit;
  /* 16px stops iOS Safari zooming into the field. */
  font-size: 16px;
  padding: 10px 12px;
  border: 1px solid var(--axis);
  border-radius: 8px;
  background: var(--page);
  color: var(--ink-1);
}
input:focus-visible,
button:focus-visible {
  outline: 2px solid var(--series-1);
  outline-offset: 2px;
}
button {
  font: inherit;
  font-weight: 600;
  padding: 10px 12px;
  min-height: 44px;
  border: 0;
  border-radius: 8px;
  background: var(--series-1);
  color: #fff;
  cursor: pointer;
}
button:disabled {
  opacity: 0.6;
  cursor: progress;
}
.error {
  margin: 0;
  color: var(--critical);
  font-size: 13px;
}
</style>
