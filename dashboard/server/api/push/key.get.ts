// The VAPID public key the browser needs to subscribe, or enabled: false if push is not set up.
export default defineEventHandler(() => {
  const config = pushConfig()
  return config ? { enabled: true, publicKey: config.publicKey } : { enabled: false, publicKey: null }
})
