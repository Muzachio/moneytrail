# ADR-0007: Google sign-in with server-side sessions

- **Status:** Accepted
- **Date:** 2026-10-14

## Context
Users are friends who should be able to sign in within seconds. Storing passwords
adds risk (hashing, resets, breaches) without making the project better. Tokens kept
in `localStorage` are exposed to XSS.

## Decision
- **Google OIDC** through Spring Security's OAuth2 client. Users are keyed on
  `(provider, subject)`, not email, so more providers can be added later.
- **Server-side sessions** stored in Postgres with Spring Session JDBC, sent as a
  `__Host-` cookie with `HttpOnly`, `Secure` and `SameSite=Lax`.
- SPA and API on subdomains of the same domain. CSRF tokens via `CookieCsrfTokenRepository`.
- No JWTs between the browser and the API.

## Consequences
- No password storage at all.
- Sessions can be revoked instantly (logout everywhere).
- Requires a custom domain so that cookies are same-site.
- A Google account is required, which is fine for the target users.
