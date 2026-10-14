/**
 * Whether the signed-in user may change services. Placeholder until the auth context from US-01
 * lands: it reads the user that login is expected to keep in sessionStorage under "user". The
 * backend refuses non-admins regardless; this only hides buttons they can't use.
 */
export function canManageServices(): boolean {
  try {
    const raw = sessionStorage.getItem('user')
    if (!raw) return false
    const user = JSON.parse(raw) as { role?: unknown }
    return user.role === 'ADMIN'
  } catch {
    return false
  }
}
