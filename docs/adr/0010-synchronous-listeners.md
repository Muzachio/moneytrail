# ADR-0010: Synchronous listeners for state the user needs at once

- **Status:** Accepted
- **Date:** 2026-11-12
- **Refines:** [ADR-0005](0005-events-and-outbox.md), which sends every cross-module event
  through the outbox

## Context
Every user needs a personal ledger with the default categories (#42). The app asks for the
categories right after sign-in, and from #46 on every transaction needs one. The ledger is
`personal`'s, while sign-in is `identity`'s, a lower-level module that must not depend on
`personal`.

ADR-0005's listeners (`@ApplicationModuleListener`) run after commit, in another thread. Opening
the ledger there leaves a window in which a signed-in user has no categories: short when all goes
well, until the next retry when the listener fails. The client would have to wait or retry, and
every endpoint would have to cope with a user without a ledger.

## Decision
- `identity` publishes `UserSignedIn` inside the sign-in transaction, on every sign-in.
- `personal` handles it with a plain `@EventListener` and
  `@Transactional(propagation = MANDATORY)`: in the same thread and transaction, before sign-in
  answers. The ledger commits with the sign-in or not at all; if opening it fails, the sign-in
  fails and the person signs in again.
- This is the exception: a listener runs synchronously only when the user needs its result at
  once and must never be without it. Everything else keeps ADR-0005's outbox.
- Synchronous listeners are idempotent, like the others, and safe when two transactions publish
  the same event at once. Here `ledgers` is unique per owner and the ledger is inserted with
  `ON CONFLICT DO NOTHING`, which makes a concurrent second insert wait for the first and then
  do nothing.

Considered and rejected:
- **`identity` calls `personal` directly:** the same timing, but `identity` would depend on
  `personal`, and on every module that later needs to set something up for new users.
- **Open the ledger lazily on the first request that needs it:** every endpoint of `personal`,
  `reports` and later modules would have to remember to. (The categories endpoints still do, as a
  fallback for a session that began before #42 was deployed.)
- **Create the ledgers of existing users in a migration:** the default categories would then be
  written twice, in Java and in SQL, and the SQL copy would never change again. Existing users get
  their ledger on their next sign-in instead.

## Consequences
- No user is ever without a ledger and categories, and endpoints may count on it.
- A slow or failing listener slows or fails sign-in, so synchronous listeners must be few and
  quick. Opening a ledger takes about 40 statements, once per user; later sign-ins read one row.
- The event is published on every sign-in, so a listener added later reaches existing users at
  their next sign-in, without a migration.
- `docs/architecture.md` ("Module boundaries", "Async work and the outbox") and `CLAUDE.md` say
  when to use which kind of listener.
