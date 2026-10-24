# CLAUDE.md

Guidance for AI assistants working in this repository. Read `docs/architecture.md`
and the ADRs in `docs/adr/` before making structural changes.

## Project
MoneyTrail: personal and shared expense tracker on a double-entry ledger.
Java 25 + Spring Boot 4 (Spring Modulith) backend, React + TypeScript frontend,
PostgreSQL. Spec-first API in `api/openapi.yaml`.
Build in roadmap order: a person's income and expenses through the month (M1), then
groups (M2). Receipt QR scanning (M3) is a future feature: don't build it or its
`invoices` module early.

## Commands
```
cd backend
./gradlew build                 # compile, test, spotless check
./gradlew test --tests '*Ledger*'
./gradlew spotlessApply         # format
./gradlew bootRun               # run locally (starts Postgres via compose.yaml)

cd frontend
npm run dev | npm test | npm run lint | npm run generate:api
```

## Non-negotiable rules (money)
- Money is `Money(long minor, Currency currency)`. **Never** `double`/`float`, not even
  in tests, JSON or the frontend. `BigDecimal` only for FX and percentage maths, with
  explicit `RoundingMode`.
- Only the `ledger` module writes `journal_entries`, `postings` and `account_balances`.
  Other modules call `LedgerService`.
- Journal entries and postings are append-only. Never write `UPDATE`/`DELETE` for them.
  Corrections are reversals.
- Every entry must balance to zero per currency. Add a test whenever a new posting
  rule is introduced.
- Lock `account_balances` rows with `FOR UPDATE` in **ascending account id order**.
- Every money-moving endpoint requires `Idempotency-Key`.
- Splits use largest-remainder rounding with deterministic ordering. Shares must always sum to the total.

## Public repository (privacy)
This repository is public (MIT). Everything committed stays visible forever, history
included, so private data must never reach a commit.
- Never commit real personal or financial data: bank statements, real receipt QR codes
  (they contain the buyer's NIF), NIFs, IBANs, emails, friends' names, screenshots with
  real amounts.
- Test data is always invented. Real data for manual testing lives only in `local/`
  (git-ignored).
- Secrets go in git-ignored `.env` files locally and in Fly/GitHub Actions secrets in
  production. Commit only a `.env.example` with placeholder values.
- Before each commit, check `git status` and `git diff --staged` for anything private.
  If something private was already committed, stop and tell the maintainer: deleting it
  in a new commit does not remove it from history.

## Code conventions
- Packages: `io.github.muzachio.moneytrail.<module>`. A module's public API sits in the
  module root package. Implementation goes in `<module>.internal`. `ModularityTests` must pass.
- Cross-module side effects go through domain events (`@ApplicationModuleListener`).
  Listeners must be idempotent.
- Controllers implement the generated OpenAPI interfaces. Change `api/openapi.yaml` first, then code.
- Use records for DTOs and value objects, sealed interfaces for results and errors,
  constructor injection only, no Lombok.
- Errors: throw domain exceptions, mapped to `ProblemDetail` with a stable `code` in one `@RestControllerAdvice`.
- Schema changes only through new Flyway migrations (`V<n>__description.sql`). Never edit an applied migration.
- No secrets, tokens or personal data in code, logs or test fixtures.
- Comments explain *why*, not *what*.

## Testing expectations
- New domain logic: unit tests, plus jqwik properties for anything arithmetic.
- New persistence or endpoints: Testcontainers integration test (real Postgres, no H2).
- Authorization: test that another user's resources return `404`.
- Run `./gradlew build` before declaring work done.

## Workflow
- Small, focused changes: one roadmap task per branch and PR.
- Conventional Commits, in English: `<type>(<scope>): <Description>`.
  - Type in lowercase: `feat`, `fix`, `refactor`, `test`, `docs`, `build`, `ci`, `chore`, `perf`.
  - Scope is a module (`ledger`, `personal`, `groups`, `budgets`, `invoices`, `imports`,
    `reports`, `identity`, `shared`) or `api`, `frontend`, `db`, `deploy`, `adr`.
  - Description starts with a capital letter, imperative mood, no final period, first
    line ≤ 72 chars. Example: `feat(ledger): Add Money record with minor units`.
  - The body explains *why*. Breaking changes use `!` (`feat(api)!: …`) plus a
    `BREAKING CHANGE:` footer.
  - No `Co-Authored-By` or other AI attribution lines.
- Branches: `<type>/<issue number>-<short-description>`, e.g. `feat/14-money-record`.
- PR title follows the commit format, the description includes `Closes #<issue>`, and PRs
  are merged with "Squash and merge".
- A change that alters architecture comes with a new ADR in `docs/adr/`.
- Explain non-obvious decisions in the PR description. The maintainer must be able to
  defend every line in an interview.
