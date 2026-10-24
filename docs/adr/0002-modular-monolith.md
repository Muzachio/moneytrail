# ADR-0002: Modular monolith instead of microservices

- **Status:** Accepted
- **Date:** 2026-10-14

## Context
I work with event-driven microservices professionally, and they solve real problems:
independent deployment, team autonomy, scaling hot paths separately. None of those
problems exist here. There is one developer, a handful of users, and a strong need for
**atomic consistency** between "record the expense" and "update the balances". Across
services that would mean sagas and eventual consistency for no benefit.

## Decision
Build one Spring Boot application split into modules (`ledger`, `personal`, `groups`,
`budgets`, `invoices`, `imports`, `reports`, `identity`), with boundaries enforced by
**Spring Modulith**:
- modules only use each other's public API,
- cross-module side effects go through domain events,
- `ApplicationModules.verify()` runs in CI and fails the build on violations.

The same artifact runs as `api` or `worker` through Spring profiles.

## Consequences
- Ledger writes are a single local ACID transaction.
- One deployable, one database, cheap to host.
- If a module ever needs to be split out (for example `imports`), its events can be
  externalised to Kafka with Spring Modulith's `@Externalized` without rewriting the
  listeners.
- Discipline is required: the module verification test is the guard rail.
