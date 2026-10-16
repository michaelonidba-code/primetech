// The dashboard only polls the gateway from the browser, so it runs as a client-side SPA.
// That lets the same build be served by Node (Docker) or as static files (GitHub Pages).
export default defineNuxtConfig({
  compatibilityDate: '2026-07-01',
  ssr: false,
  devtools: { enabled: false },
  css: ['~/assets/main.css'],
  app: {
    head: {
      title: 'Ads Eventing',
      meta: [{ name: 'viewport', content: 'width=device-width, initial-scale=1' }],
    },
  },
  runtimeConfig: {
    public: {
      // Overridden at runtime with NUXT_PUBLIC_GATEWAY_URL, or per visit with ?gateway=<url>.
      gatewayUrl: 'http://localhost:8080',
      pollMs: 2000,
    },
  },
  typescript: { strict: true },
})
