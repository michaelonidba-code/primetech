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
    // YouTube growth console. Google settings (API key, OAuth client, Ads developer token, refresh
    // token) are entered in the console and stored server-side in this file (mode 600).
    googleStorePath: '.data/google.json', // NUXT_GOOGLE_STORE_PATH
    youtubeHistoryPath: '.data/youtube-history.jsonl', // NUXT_YOUTUBE_HISTORY_PATH
    adsActionsLogPath: '.data/ads-actions.jsonl', // NUXT_ADS_ACTIONS_LOG_PATH: who launched/started/paused what
    googleAdsApiVersion: 'v26', // NUXT_GOOGLE_ADS_API_VERSION
    adsMaxDailyBudget: 1000, // NUXT_ADS_MAX_DAILY_BUDGET: cap on the daily budget the console accepts
    // Sponsor ad network. The store is private; the catalog is read by the ad server (CATALOG_FILE);
    // the media directory (videos + creatives.json) is served publicly at /media by Caddy.
    sponsorsStorePath: '.data/sponsors.json', // NUXT_SPONSORS_STORE_PATH
    sponsorsActionsLogPath: '.data/sponsor-actions.jsonl', // NUXT_SPONSORS_ACTIONS_LOG_PATH
    sponsorsCatalogPath: 'catalog/catalog.json', // NUXT_SPONSORS_CATALOG_PATH
    sponsorsMediaDir: 'media', // NUXT_SPONSORS_MEDIA_DIR
    pacingPeriodMinutes: 30, // NUXT_PACING_PERIOD_MINUTES: must match the ad server and pacing service
    sponsorFloorCpm: 5, // NUXT_SPONSOR_FLOOR_CPM: must be at least the ad server's FLOOR_CPM
    sponsorMaxUploadMb: 80, // NUXT_SPONSOR_MAX_UPLOAD_MB
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
