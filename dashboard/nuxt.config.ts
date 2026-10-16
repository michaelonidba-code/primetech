// A client-side SPA: the same build is served by Node (Docker) or as static files (GitHub Pages).
// Under Node, the server routes in server/api also proxy Flink and Druid; on Pages those
// panels say they need the Docker stack.
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
    // Server-only. Overridden with NUXT_FLINK_URL and NUXT_DRUID_URL.
    flinkUrl: 'http://localhost:8082',
    druidUrl: 'http://localhost:8888',
    public: {
      // Overridden at runtime with NUXT_PUBLIC_GATEWAY_URL, or per visit with ?gateway=<url>.
      gatewayUrl: 'http://localhost:8080',
      pollMs: 2000,
    },
  },
  typescript: { strict: true },
})
