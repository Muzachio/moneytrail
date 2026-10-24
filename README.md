# MoneyTrail

**Personal and shared expense tracking on top of a real double-entry ledger.**

Record your income and expenses through the month and see where the money goes. Next,
split costs with friends in groups. Later, log a purchase by scanning the QR code on any
Portuguese receipt. Under the hood it is built like a payments system: immutable journal
entries, integer money, idempotent APIs, safe concurrency and a transactional outbox.

> 🚧 **Status:** design phase. See the [roadmap](docs/roadmap.md).
> Live demo: *coming with M0*.

## Features (planned, in this order)

1. **Personal finance (v0.1, current focus):** record a person's income and expenses
   through the month, accounts, transfers, categories, monthly dashboard
2. **Groups (v0.2):** shared expenses with flexible splits, balances, minimum-payment settle-up
3. **Later (v0.3+):**
   - **Receipt scan:** reads the Portuguese ATCUD fiscal QR code and prefills the transaction
   - **Automation:** recurring transactions, budgets with alerts, CSV bank statement import
   - **Multi-currency** with ECB reference rates

## Engineering highlights

| | |
|---|---|
| **Double-entry ledger** | Every movement is a balanced journal entry. Invariants are enforced in code *and* in Postgres (deferred constraint triggers, append-only tables) → [ADR-0004](docs/adr/0004-double-entry-ledger.md) |
| **Idempotent writes** | `Idempotency-Key` on every money-moving request, with responses replayed safely |
| **Concurrency** | Pessimistic row locks in a deterministic order, covered by parallel integration tests |
| **Modular monolith** | Spring Modulith boundaries verified in CI → [ADR-0002](docs/adr/0002-modular-monolith.md) |
| **Transactional outbox** | Domain events persisted atomically with business data → [ADR-0005](docs/adr/0005-events-and-outbox.md) |
| **Spec-first API** | OpenAPI 3.1 generates both the Spring interfaces and the TypeScript client → [ADR-0006](docs/adr/0006-spec-first-openapi.md) |
| **Testing** | Property-based tests (jqwik), Testcontainers, module tests, k6 load tests |
| **Observability** | OpenTelemetry traces, Micrometer metrics, structured logs → Grafana |

## Tech stack

**Backend:** Java 25 · Spring Boot 4 · Spring Modulith · Spring Security (OAuth2) · Spring Data JPA · Flyway · PostgreSQL
**Frontend:** React · TypeScript · Vite · TanStack Query · Tailwind CSS · shadcn/ui · PWA
**Infra:** Docker · GitHub Actions · Fly.io · Neon · Cloudflare Pages · Grafana Cloud

## Repository layout

```
moneytrail/
├── api/openapi.yaml      # API contract (source of truth)
├── backend/              # Spring Boot application (Gradle)
├── frontend/             # React SPA (Vite)
├── deploy/               # fly.toml and deployment config
├── docs/
│   ├── product.md        # what and why
│   ├── architecture.md   # how
│   ├── roadmap.md        # milestones
│   ├── setup.md          # dev environment
│   └── adr/              # architecture decision records
└── .github/workflows/    # CI/CD
```

## Running locally

See [docs/setup.md](docs/setup.md).

## License

MIT
