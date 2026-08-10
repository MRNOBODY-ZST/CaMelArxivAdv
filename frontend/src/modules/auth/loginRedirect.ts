const FORCED_PASSWORD_PATH = '/change-password'

export function safePostLoginRedirect(requested: unknown): string {
  if (typeof requested !== 'string' || !requested.startsWith('/') || requested.startsWith('//')) {
    return '/'
  }
  const path = requested.split(/[?#]/, 1)[0]
  if (path === '/login' || path === FORCED_PASSWORD_PATH) return '/'
  return requested
}
