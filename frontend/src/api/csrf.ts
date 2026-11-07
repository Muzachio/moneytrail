const CSRF_COOKIE = 'XSRF-TOKEN'

/** Requests that change nothing on the server; the API only checks the CSRF token on the others. */
export const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS', 'TRACE'])

/**
 * The CSRF token the API keeps in a cookie that JavaScript may read (Spring Security's SPA mode,
 * ADR-0007). A page on another site cannot read it, so echoing it in the X-XSRF-TOKEN header
 * proves the request comes from MoneyTrail.
 */
export function csrfHeaders(): Record<string, string> {
  const cookie = document.cookie
    .split('; ')
    .find((entry) => entry.startsWith(`${CSRF_COOKIE}=`))
  if (cookie === undefined) {
    return {}
  }
  return {
    'X-XSRF-TOKEN': decodeURIComponent(cookie.slice(CSRF_COOKIE.length + 1)),
  }
}
