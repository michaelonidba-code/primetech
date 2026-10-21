// Revokes and forgets the Google sign-in. The rest of the settings stay.
export default defineEventHandler(async (event) => {
  requireSameOrigin(event)
  await disconnectGoogle()
  return googleStatus(event)
})
