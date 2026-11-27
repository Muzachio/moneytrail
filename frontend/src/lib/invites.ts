// An invite link is `<app>/join#<token>` (ADR-0013). What follows `#` never leaves the browser: it
// is not sent with any request, logged by any server or put in a `Referer`. The join page keeps the
// token in sessionStorage while the person signs in with Google, which is a full page navigation
// away and back, and sends it to the API only in request bodies.

const TOKEN_KEY = 'moneytrail.invite'
const RETURN_KEY = 'moneytrail.invite.returnAfterSignIn'

/** 32 random bytes in base64url without padding, as `InviteToken` in the spec. */
const TOKEN = /^[A-Za-z0-9_-]{43}$/

/** The link to send: the token after `#`, on the app's own origin. */
export function joinLink(token: string, origin = window.location.origin) {
  return `${origin}/join#${token}`
}

/** The token in a location's `hash` ("#abc…"), or `null` when it holds none. */
export function tokenFromHash(hash: string): string | null {
  const token = hash.replace(/^#/, '')
  return TOKEN.test(token) ? token : null
}

// sessionStorage can throw (blocked storage, some private modes): the join page then still works
// for a person who is signed in already, which needs no round trip through Google.

/** The invite this tab is opening, kept through the Google sign-in. */
export function pendingInvite(): string | null {
  try {
    const token = sessionStorage.getItem(TOKEN_KEY)
    return token !== null && TOKEN.test(token) ? token : null
  } catch {
    return null
  }
}

export function keepPendingInvite(token: string) {
  try {
    sessionStorage.setItem(TOKEN_KEY, token)
  } catch {
    // See above.
  }
}

/** Forgets the invite: joined, no longer valid, or set aside by the person. */
export function forgetPendingInvite() {
  try {
    sessionStorage.removeItem(TOKEN_KEY)
    sessionStorage.removeItem(RETURN_KEY)
  } catch {
    // See above.
  }
}

/**
 * Called as the join page sends the person to Google. The API brings everyone back to the app's
 * root (`/`, or `/?sign-in=not-invited` when the account is not on the invite list), so the app
 * needs this mark to know it should go on to the join page instead of the summary.
 */
export function markReturnAfterSignIn() {
  try {
    sessionStorage.setItem(RETURN_KEY, '1')
  } catch {
    // See above.
  }
}

/** Whether the app has just come back from a sign-in that started on the join page. */
export function returningFromSignIn(): boolean {
  try {
    return (
      sessionStorage.getItem(RETURN_KEY) === '1' && pendingInvite() !== null
    )
  } catch {
    return false
  }
}

/** Taken off by the join page once it is showing, so the next visit to `/` is a normal one. */
export function clearReturnAfterSignIn() {
  try {
    sessionStorage.removeItem(RETURN_KEY)
  } catch {
    // See above.
  }
}
