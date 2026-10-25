# Roadmap — MoneyTrail

Each milestone ends **deployed and usable**. Tasks become GitHub issues under a
milestone with the same name. One PR per issue, Conventional Commits.

Priority order: a person's income and expenses through the month (M1), then groups
(M2). Receipt QR scanning (M3) is a future feature, not part of the initial phases.

## M0 — Foundations ("walking skeleton")
Goal: an empty app deployed end to end, so every later feature ships on a working pipeline.

- [ ] Repository on GitHub: README, license (MIT), branch protection on `main`, Renovate
- [x] `backend/`: Spring Boot 4 + Java 25, Gradle Kotlin DSL, version catalog, Spotless, Error Prone/NullAway
- [x] Spring Modulith skeleton with `ModularityTests`
- [x] Postgres via `compose.yaml` + Spring Boot docker-compose support. Flyway `V1__baseline.sql`
- [x] Testcontainers base test class (`@ServiceConnection`)
- [x] `api/openapi.yaml` with `GET /v1/health` and `GET /v1/me`. Server interfaces generated
- [x] `frontend/`: Vite + React + TS, Tailwind + shadcn/ui, generated client, layout shell
- [ ] Google login end to end (Spring Security OAuth2 + Spring Session JDBC)
- [ ] CI (GitHub Actions): build, test, spec lint, frontend lint/test, image build and scan
- [ ] CD: Fly.io app (api + worker process groups), Neon database, Cloudflare Pages, custom domain
- [ ] Structured logs, Actuator health, OTLP export to Grafana Cloud

## M1 — Personal finance MVP (v0.1)
- [ ] `ledger`: `Money`, accounts, journal entries, postings, `LedgerService.post()` with validation
- [ ] DB guarantees: deferred balance trigger, immutability triggers, composite currency FK
- [ ] `account_balances` projection with ordered `FOR UPDATE` locking
- [ ] jqwik property tests + concurrency test for the ledger
- [ ] Idempotency-Key interceptor and storage
- [ ] Default categories created on sign-up (as EXPENSE/INCOME accounts)
- [ ] Accounts API + UI (create, rename, archive, balance)
- [ ] Transactions API + UI: expense, income, transfer, reverse/edit. Cursor-paginated list with filters
- [ ] Reports: spend by category (month), 6-month trend, dashboard UI
- [ ] PWA manifest and install prompt, PT/EN translations
- [ ] Invite the first 3 friends 🎉

## M2 — Groups (v0.2)
- [ ] Groups, members, invite links (hashed tokens, expiry)
- [ ] Shared expense with equal / exact / percentage / shares splits, largest-remainder rounding
- [ ] Group balances and settle-up suggestions (greedy min-payments)
- [ ] Settlements
- [ ] Optional mirror of my share into my personal ledger
- [ ] `GroupExpenseAdded` event → in-app activity feed
- [ ] Cross-tenant authorization test suite

## M3 — Automation (v0.3, future)
- [ ] ATCUD QR parser (unit-tested against anonymised receipt samples: invented NIFs, dates and amounts) + `POST /v1/invoices/parse`
- [ ] Camera scanning in the PWA. Merchant memory (tax ID → name + category)
- [ ] Recurring rules + daily scheduled job (ShedLock)
- [ ] Budgets + `ExpenseRecorded` listener → email alerts via Resend at 80% / 100%
- [ ] CSV import: upload, column mapping, async parsing, duplicate detection, report

## M4 — Depth (v0.4, future)
- [ ] Multi-currency accounts, daily ECB rates, FX postings via a trading account
- [ ] Nightly ledger invariant job + alert
- [ ] Data export (JSON/CSV) and account deletion (GDPR)
- [ ] Grafana dashboard: RED metrics, incomplete event publications, job health
- [ ] k6 load test in CI (on demand). Results published in the README
- [ ] Optional: externalise events to Kafka (Redpanda) behind a compose profile

## M5 — Platform (optional)
- [ ] ADR: move to Hetzner with Terraform + k3s + Helm + Argo CD
- [ ] Backups with pgBackRest to object storage, plus a documented restore drill
- [ ] GraalVM native image experiment, with startup and memory numbers in an ADR
