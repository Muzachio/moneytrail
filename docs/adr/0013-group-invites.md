# ADR-0013: Group invites

- **Status:** Accepted
- **Date:** 2026-11-22
- **Related:** [ADR-0007](0007-authentication.md), and the invite-only sign-in in
  [deploy.md](../deploy.md#who-can-sign-in)

## Context
People join a group through a link that a member sends them, usually in a chat. Whoever holds the
link can join, so the link is a credential, and it travels through places the app does not
control: chat apps, forwarded messages, screenshots.

A secret in a URL also leaks inside the app's own systems. Paths and query strings end up in the
API's logs, in the request traces sent to Grafana Cloud, in the browser's history and in the
`Referer` of the next request. And the app is already invite-only: only the Google accounts whose
email is in the Fly secret `MONEYTRAIL_INVITED_EMAILS` can sign in.

## Decision
- **The token** is 32 random bytes from `SecureRandom` (256 bits), written in base64url. The API
  shows it once, when the link is made; the database keeps only its SHA-256
  (`group_invites.token_hash`, unique). A plain hash is enough because the token is random:
  unlike a password there is nothing to guess, so a copy of the database cannot be turned back
  into working links, and the hash is looked up through its index.
- **Lifetime:** a link works for 7 days, for any number of people, so one link in a group chat is
  enough. Any member can make links, see the group's active ones (who made each and when it
  expires, never the token) and revoke them.
- **Where the token travels:** the link is `https://moneytrail.muzachio.dev/join#<token>`. What
  follows `#` stays in the browser: it is not sent with requests, not logged by any server and not
  part of a `Referer`. The join page keeps the token in `sessionStorage` while the person signs in
  with Google, then sends it in the body of `POST /v1/invites/lookup` (which group, who invited)
  and `POST /v1/invites/accept`. No path or query of the API carries a token, and nothing logs one.
- **Both need a signed-in person.** Someone holding a forwarded link learns nothing, not even the
  group's name, until they are someone the app lets in.
- **Accepting** adds the person to the group with a new `GROUP_MEMBER` account
  ([ADR-0012](0012-groups-on-the-ledger.md)), or gives back their old one if they had left, and
  publishes `MemberJoined`. Accepting again changes nothing.
- **An unknown, expired or revoked token, or a token of an archived group, all get the same
  answer**, so trying tokens teaches nothing about which ones exist.
- **A link lets someone into a group, never into the app.** Sign-in still checks
  `MONEYTRAIL_INVITED_EMAILS`, and someone who is not on it gets the invite-only message. The app
  holds friends' financial data, so a forwarded link must not open it to a stranger.
- **Only people who accepted an invite are members**, and only members take part in splits. There
  are no members without an account, typed in by name before they join: the app keeps nothing
  about people who never agreed to it, every member's account belongs to a real person, and being
  a member is the whole rule for who may see a group.

Considered and rejected:
- **The token in the path** (`POST /v1/invites/{token}/accept`, as `architecture.md` first had
  it): it would reach the logs and traces above.
- **A link that also lets its holder sign in**: one step less for the maintainer for each new
  friend, but every group link would become a key to the whole app. To revisit if the app opens
  to more people.
- **Members without an account**, as some expense-splitting apps allow: handy on a trip with
  someone who will not install the app, but the app would keep names and debts of people who
  never agreed to it, and their shares would need someone to claim them later.
- **Single-use links, or links for one email address**: tighter, but a member would need a link
  per friend and their Google address, which is what the sign-in list already asks of the
  maintainer.

## Consequences
- A leaked database or log holds no working link. A link that leaks anyway can be revoked, and
  stops working within a week regardless.
- Every new person still needs the maintainer to add their email to `MONEYTRAIL_INVITED_EMAILS`.
  The join page tells whoever is not let in to ask the person who sent the link.
- The join page has to carry the token through the Google sign-in in `sessionStorage` (#91), and a
  test checks that accepting an invite logs no token (#87).
- Members see each other's name and photo and the group's expenses, never another member's email
  or personal accounts. The privacy page says so (#91).
