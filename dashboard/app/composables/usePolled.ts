/** Polls a same-origin server route. `data` keeps the last good response while a poll fails. */
export function usePolled<T>(path: string, everyMs: number) {
  const data = ref<T | null>(null)
  const reachable = ref(true)
  const session = useSession()

  useVisibleInterval(async () => {
    try {
      data.value = await $fetch<T>(path, { timeout: 5000 })
      reachable.value = true
    } catch (err) {
      // No server routes (the static GitHub Pages build), offline, or signed out.
      reachable.value = false
      session.noteError(err)
    }
  }, everyMs)

  return { data, reachable }
}
