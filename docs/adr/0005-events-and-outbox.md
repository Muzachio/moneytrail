# ADR-0005: Domain events through Spring Modulith's outbox, not Kafka

- **Status:** Accepted
- **Date:** 2026-10-14

## Context
Several actions trigger work that should not block the request or fail it: budget
checks after an expense, parsing an uploaded CSV, notifying group members. The usual
choice would be Kafka with a transactional outbox (for example Debezium). For this
project that means paying for and operating a broker for a few events per minute.

## Decision
Use **Spring Modulith's event publication registry** (JDBC):
- events are published inside the business transaction and stored in
  `event_publication` atomically with the business data, so no dual write is possible,
- `@ApplicationModuleListener` handlers run asynchronously after commit,
- incomplete publications are resubmitted on restart and on a schedule, and their
  count is exported as a metric,
- every listener is idempotent, because delivery is at-least-once.

Scheduled jobs use `@Scheduled` with **ShedLock**, so only one instance runs each job.

## Consequences
- No extra infrastructure. Everything lives in Postgres.
- If external consumers appear, events can be externalised to Kafka (`@Externalized`).
  A docker compose profile with Redpanda can demonstrate this locally.
- Not suitable for high-throughput streaming, which this project does not need.
