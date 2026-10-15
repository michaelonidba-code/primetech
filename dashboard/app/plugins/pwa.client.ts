// Registers the service worker (public/sw.js) for production builds, under the app's base URL so
// the same build works at / (Docker, hosted) and /primetech/ (GitHub Pages).
export default defineNuxtPlugin(() => {
  if (import.meta.dev || !('serviceWorker' in navigator) || !window.isSecureContext) return
  const base = useRuntimeConfig().app.baseURL
  window.addEventListener('load', () => {
    navigator.serviceWorker.register(`${base}sw.js`, { scope: base }).catch((err) => {
      console.warn('service worker registration failed', err)
    })
  })
})
