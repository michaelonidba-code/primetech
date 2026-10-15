/**
 * Runs `fn` now and every `everyMs` while the page is visible and the device is online. Hidden
 * tabs, backgrounded phone apps and offline devices stop polling (saving battery and data) and
 * catch up as soon as they are shown again or reconnect.
 */
export function useVisibleInterval(task: () => void, everyMs: number) {
  let timer: ReturnType<typeof setInterval> | undefined
  const fn = () => {
    if (navigator.onLine) task()
  }

  function start() {
    stop()
    fn()
    timer = setInterval(fn, everyMs)
  }
  function stop() {
    clearInterval(timer)
    timer = undefined
  }
  function onVisibility() {
    if (document.visibilityState === 'visible') start()
    else stop()
  }

  onMounted(() => {
    if (document.visibilityState === 'visible') start()
    document.addEventListener('visibilitychange', onVisibility)
    window.addEventListener('online', onVisibility)
  })
  onBeforeUnmount(() => {
    stop()
    document.removeEventListener('visibilitychange', onVisibility)
    window.removeEventListener('online', onVisibility)
  })
}
