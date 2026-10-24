# ADR-0004: Immutable double-entry ledger with integer money

- **Status:** Accepted
- **Date:** 2026-10-14

## Context
The simple design, a `transactions` table with an `amount` column and a mutable
`balance` on each account, breaks in predictable ways. Edits lose history, concurrent
updates corrupt balances, floats introduce rounding errors, and group debts become
special cases spread across the code.

## Decision
- Every movement is a **journal entry** with ≥ 2 **postings** whose signed amounts sum
  to zero per currency.
- Amounts are `bigint` **minor units** plus an ISO 4217 currency, wrapped in a
  `Money` record in Java. No `double`/`float` anywhere, including JSON.
- Entries are **append-only**. Corrections are reversals plus new entries.
- Categories are `EXPENSE`/`INCOME` accounts. Group debts are `GROUP_MEMBER` accounts
  in a group-owned ledger.
- `account_balances` is a materialised projection updated in the same transaction
  under `SELECT … FOR UPDATE`, locked in ascending account id order.
- The invariants are enforced twice: in `LedgerService` and in the database
  (deferred constraint trigger, immutability triggers, composite FKs).

## Consequences
- Full audit trail for free, and every balance can be rebuilt.
- Personal finance, groups, settlements and later multi-currency all share one model.
- More rows per movement and a steeper learning curve than a single `amount` column,
  which is acceptable.
