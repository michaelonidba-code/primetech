// A client-side SPA: the same build is served by Node (Docker, or hosted behind Caddy) or as static
// files (GitHub Pages). Under Node, the server routes in server/api proxy the gateway, Flink, Druid,
// the ad server and the pacing service, behind a login unless NUXT_AUTH_DISABLED=true. On Pages
// those panels say they need the Docker stack.
const base = process.env.NUXT_APP_BASE_URL || '/'

export default defineNuxtConfig({
  compatibilityDate: '2026-07-01',
  ssr: false,
  devtools: { enabled: false },
  css: ['@fontsource/orbitron/latin-600.css', '@fontsource/orbitron/latin-800.css', '~/assets/main.css'],
  app: {
    head: {
      title: 'Ads Eventing',
      meta: [
        // viewport-fit=cover lets the installed app draw under phone notches; main.css pads with safe-area insets.
        { name: 'viewport', content: 'width=device-width, initial-scale=1, viewport-fit=cover' },
        { name: 'description', content: 'Live ads eventing pipeline: ad serving, pacing, stream processing and delivery. By Quila Visuals.' },
        { name: 'author', content: 'Quila Visuals' },
        { name: 'theme-color', content: '#03060c' },
        { name: 'apple-mobile-web-app-capable', content: 'yes' },
        { name: 'mobile-web-app-capable', content: 'yes' },
        { name: 'apple-mobile-web-app-status-bar-style', content: 'black-translucent' },
        { name: 'apple-mobile-web-app-title', content: 'Ads Eventing' },
      ],
      link: [
        { rel: 'manifest', href: `${base}manifest.webmanifest` },
        { rel: 'icon', type: 'image/png', sizes: '32x32', href: `${base}icons/favicon-32.png` },
        { rel: 'apple-touch-icon', href: `${base}icons/apple-touch-icon.png` },
      ],
    },
  },
  runtimeConfig: {
    // Server-only. Overridden with NUXT_GATEWAY_URL, NUXT_FLINK_URL, NUXT_DRUID_URL,
    // NUXT_AD_SERVER_URL, NUXT_PACING_URL.
    gatewayUrl: 'http://localhost:8080',
    flinkUrl: 'http://localhost:8082',
    druidUrl: 'http://localhost:8888',
    adServerUrl: 'http://localhost:8090',
    pacingUrl: 'http://localhost:8095',
    auditUrl: 'http://localhost:8096', // NUXT_AUDIT_URL
    alertmanagerUrl: 'http://localhost:9093', // NUXT_ALERTMANAGER_URL
    // Alertmanager's webhook (/api/alerts/webhook) must send this as a bearer token. NUXT_ALERT_WEBHOOK_TOKEN
    alertWebhookToken: '',
    // Web Push for alerts on installed apps. Generate keys with `npx web-push generate-vapid-keys`.
    vapidPublicKey: '', // NUXT_VAPID_PUBLIC_KEY
    vapidPrivateKey: '', // NUXT_VAPID_PRIVATE_KEY
    vapidSubject: '', // NUXT_VAPID_SUBJECT, e.g. mailto:ops@example.com
    pushStorePath: '.data/push-subscriptions.json', // NUXT_PUSH_STORE_PATH
    pushAllowedHosts: '', // NUXT_PUSH_ALLOWED_HOSTS: extra push service hosts (tests)
    // Authentication (see README "Hosting"). On unless NUXT_AUTH_DISABLED=true; if on and
    // misconfigured, the API answers 503 instead of serving data.
    authUsers: '', // NUXT_AUTH_USERS: user=scrypt-hash,... from `npm run hash-password -- <user>`
    sessionSecret: '', // NUXT_SESSION_SECRET: at least 32 random characters
    sessionTtlHours: 12, // NUXT_SESSION_TTL_HOURS
    authDisabled: false, // NUXT_AUTH_DISABLED
    trustProxy: false, // NUXT_TRUST_PROXY: true behind Caddy, to read the client address and scheme
    public: {
      // "proxy": the browser reads gateway stats through /api/gateway (Node builds).
      // "direct": it calls the gateway itself (the static Pages build, or ?gateway=<url>).
      gatewayMode: 'proxy', // NUXT_PUBLIC_GATEWAY_MODE
      gatewayUrl: 'http://localhost:8080', // NUXT_PUBLIC_GATEWAY_URL, used in direct mode
      pollMs: 2000,
    },
  },
  typescript: { strict: true },
})
