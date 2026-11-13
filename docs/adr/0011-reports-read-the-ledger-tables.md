# ADR-0011: Reports read the ledger's tables with SQL

- **Status:** Accepted
- **Date:** 2026-11-13
- **Refines:** [ADR-0002](0002-modular-monolith.md), where modules only use each other's public API

## Context
The dashboard (#49) needs a period's spending by category and the income and spending of each of
the last six months (#48). Both are sums of postings to categories, grouped by category or by
month: one `GROUP BY` over `journal_entries`, `postings` and `accounts`, which only the `ledger`
module writes.

`LedgerService` is the ledger's write side, plus the reads the use cases need (`balances`,
`changes`, `entries`, `totals`). Reading through it would mean adding a method for every report,
grouping and filter, so the ledger would end up knowing about every screen that shows a sum. The
reports would also not be one query each.

## Decision
- The `reports` module is the read side of the ledger. It reads the ledger's tables with
  `JdbcClient` and never writes them: only `ledger` writes (ADR-0004), and that does not change.
- The SQL lives in one class, `reports.internal.CategorySums`. It adds up postings and needs no
  special case for transfers, opening balances, reversals or edits: the ledger's rules make the
  plain sum right (a reversal is the exact opposite of its entry, on the same day).
- What only `personal` knows comes through its public API (`PersonalLedgerQueries`): which ledger
  is the user's, its currency, and how the API names its categories. The ledger's id comes from the
  signed-in user, never from the request.

## Consequences
- Each report is one query that the index on `(ledger_id, occurred_on, id)` (V10) serves.
- Spring Modulith sees `reports` depend on `personal`, `identity` and `shared`, but cannot see that
  it reads the ledger's tables. This ADR and the `reports` package documentation say so instead.
- The ledger's schema is now a contract with `reports` too. A migration that changes those tables
  must keep the reports' query working; its Testcontainers tests run it against the real schema,
  so a break fails the build.
- Considered and rejected:
  - **A `LedgerService` method per report:** the ledger would grow with every screen, as above.
  - **A separate read model** (a table of monthly sums per category, updated by events): a second
    copy of the numbers that can drift from the ledger and must be rebuilt when it does. The plain
    query is fast enough for one person's entries; a projection can come if it ever is not.
