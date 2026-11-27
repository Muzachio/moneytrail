# ADR-0014: The activity feed, the first listener through the outbox

- **Status:** Accepted
- **Date:** 2026-11-27
- **Refines:** [ADR-0005](0005-events-and-outbox.md), where incomplete publications were to be
  resubmitted on restart as well as on a schedule
- **Related:** [ADR-0010](0010-synchronous-listeners.md), [ADR-0012](0012-groups-on-the-ledger.md)

## Context
A group's members want to see what happened in it: who joined or left, and each expense and
payment recorded, edited or reversed (#90). The `groups` module already publishes an event for
each of these, in the transaction that makes the change.

The feed is not something a member needs the instant a change is saved, and a failure to write it
must never fail the change. That is the case ADR-0005 describes, and the opposite of ADR-0010's
synchronous listener. Until now no listener has gone through the outbox, so the app had no
`event_publication` table, nothing to resubmit what fails, and no rule for where listeners run.

Production runs on Neon's free plan, whose compute sleeps after a few idle minutes and whose
monthly allowance counts the time it is awake. The app already deletes expired sessions at the top
of every hour, and the idempotency-key cleanup runs in one of those minutes (#81), so the database
is woken at most once an hour by work on a timer.

## Decision

### A module of its own
- A new `activity` module listens to the events of `groups` and keeps one row per event in
  `activity_items`. `groups` does not depend on it: the events are part of `groups`' public API,
  and the feed reads nothing else from `groups` but whether the reader is a member now, through a
  small public interface, `GroupMembership`.
- Each row keeps what the feed shows as it was at that moment (descriptions, categories, amounts,
  who paid whom), so reading the feed never asks `groups`, and an edit adds a row instead of
  changing one. What an event carries and the row does not keep cannot be added later: the event
  is gone once delivered.
- `GET /v1/groups/{id}/activity` returns the feed newest first, with keyset pagination on the
  time of the change and the row's id, and the profile of everyone the page names, former members
  included. Anyone who is not a member now gets the same `404 group_not_found` as every group
  endpoint.

### Through the outbox
- Each listener method is an `@ApplicationModuleListener`. Spring Modulith's event publication
  registry (JDBC, `spring-modulith-starter-jdbc`) stores a publication in `event_publication` in
  the transaction that publishes the event, and calls the listener after it commits, in another
  thread and a transaction of its own. Flyway creates the table (V19), with the columns of Spring
  Modulith 2.2.
- The listener runs in the process that published the event: in production the `api`, since only
  requests publish these events. The `worker` gets no event unless it publishes one itself.
- A completed publication is deleted at once (`completion-mode: delete`): the feed's rows are the
  record, and the table holds only what is still to be delivered.
- Delivery is at least once, so the listener is idempotent: `activity_items` is unique on the group,
  the kind, the subject and the time of the change, and an insert that hits it does nothing.

### Resubmitting what did not complete
- A listener that throws leaves its publication `FAILED`. One that a process stopped in the middle
  of, or that never started, stays `PUBLISHED`, `PROCESSING` or `RESUBMITTED`.
- One job, `shared.events.IncompletePublications`, runs at the top of every hour with ShedLock,
  where the scheduled jobs run. It counts the publications not completed after 10 minutes; when
  there are any, it marks the stale ones failed and resubmits every failed one. When there are
  none, the run is one query, in a minute when the database is awake anyway.
- Resubmitting on restart stays off (Spring Modulith's default). With it on, an `api` and a
  `worker` starting together would both resubmit, and a deploy would add a query of its own.
- Spring Modulith's staleness check is left unset. Set, it would run on a timer of its own, every
  minute by default, each run a query that wakes Neon. Unset, it schedules nothing, and the hourly
  job does its work instead, at the top of the hour.
- The gauge `moneytrail.event.publications.incomplete` reports the number the last run found,
  kept in memory, so exporting metrics every minute never queries the database. It reports no
  value until the first run.

Considered and rejected:
- **Writing the feed in the same transaction as the change**, with a synchronous listener: the
  feed would be complete at once, but a failure to write it would fail the expense or payment,
  which ADR-0010 keeps for state the user cannot do without.
- **Building the feed on read from the groups tables and the group's ledger**: no table and no
  listener, but `activity` would read `groups`' tables, or `groups` would carry the feed's reads,
  and joins and leaves would need a history `groups` does not keep.
- **Spring Modulith's `IncompleteEventPublications`**, which resubmits every incomplete publication
  in one call: deprecated for removal in 2.2, in favour of `FailedEventPublications`, which only
  takes failed ones; hence the job's own staleness step.

## Consequences
- What a member does in a group shows in the feed a moment later, or within the hour when the
  listener failed. The feed's endpoint says so.
- `event_publication` holds only what is still to be delivered, and the gauge says how much of it
  is old. A number above zero for several hours means a listener that keeps failing.
- The job uses Spring Modulith's registry SPI (`EventPublicationRegistry`, `Staleness`) besides its
  public API, so an upgrade of Spring Modulith is checked against `IncompletePublicationsTests`,
  and a change to `event_publication`'s columns needs a migration.
- The next listeners through the outbox (budget alerts, imports) get resubmission and the gauge
  without more work.
- `docs/architecture.md` ("Module boundaries", "Async work and the outbox", the data model and the
  API) and `CLAUDE.md` (the commit scopes) say the same.
