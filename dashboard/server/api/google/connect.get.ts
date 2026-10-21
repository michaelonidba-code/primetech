// Starts "Connect Google": sends the signed-in user to Google's consent screen.
export default defineEventHandler(async (event) => {
  const url = await oauthStartUrl(event, (event.context.user as string | undefined) ?? 'local')
  return sendRedirect(event, url, 302)
})
