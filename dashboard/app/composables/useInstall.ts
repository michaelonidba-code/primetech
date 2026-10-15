interface BeforeInstallPromptEvent extends Event {
  prompt(): Promise<void>
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>
}

/**
 * App installation. Chromium browsers (desktop, Android) offer a prompt we can trigger from a
 * button; iOS Safari has no prompt, so we explain Share → Add to Home Screen instead.
 */
export function useInstall() {
  const deferred = shallowRef<BeforeInstallPromptEvent | null>(null)
  const installed = ref(false)
  const iosHint = ref(false)

  function standalone() {
    return window.matchMedia('(display-mode: standalone)').matches
      || (navigator as Navigator & { standalone?: boolean }).standalone === true
  }

  function onPrompt(e: Event) {
    e.preventDefault()
    deferred.value = e as BeforeInstallPromptEvent
  }
  function onInstalled() {
    installed.value = true
    deferred.value = null
  }

  onMounted(() => {
    installed.value = standalone()
    iosHint.value = !installed.value && /iPhone|iPad|iPod/.test(navigator.userAgent)
    window.addEventListener('beforeinstallprompt', onPrompt)
    window.addEventListener('appinstalled', onInstalled)
  })
  onBeforeUnmount(() => {
    window.removeEventListener('beforeinstallprompt', onPrompt)
    window.removeEventListener('appinstalled', onInstalled)
  })

  async function install() {
    const e = deferred.value
    if (!e) return
    deferred.value = null
    await e.prompt()
    if ((await e.userChoice).outcome === 'accepted') installed.value = true
  }

  const canPrompt = computed(() => deferred.value !== null && !installed.value)
  return { canPrompt, iosHint, installed, install }
}
