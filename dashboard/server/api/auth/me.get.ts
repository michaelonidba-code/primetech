// Who is signed in. Reaching this handler means the middleware accepted the session (or auth is off).
export default defineEventHandler((event) => ({
  user: (event.context.user as string | undefined) ?? null,
  authDisabled: authState().disabled,
}))
