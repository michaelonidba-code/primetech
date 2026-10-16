/** Polls a same-origin server route. `data` keeps the last good response while a poll fails. */
export function usePolled<T>(path: string, everyMs: number) {
  const data = ref<T | null>(null)
  const reachable = ref(true)
  let timer: ReturnType<typeof setInterval> | undefined

  async function load() {
    try {
      data.value = await $fetch<T>(path, { timeout: 5000 })
      reachable.value = true
    } catch {
      // No server routes, e.g. the static GitHub Pages build.
      reachable.value = false
    }
  }

  onMounted(() => {
    load()
    timer = setInterval(load, everyMs)
  })
  onBeforeUnmount(() => clearInterval(timer))

  return { data, reachable }
}
