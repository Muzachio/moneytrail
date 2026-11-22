# ADR-0012: Groups on the ledger

- **Status:** Accepted
- **Date:** 2026-11-22
- **Refines:** [ADR-0004](0004-double-entry-ledger.md), which keeps group debts in `GROUP_MEMBER`
  accounts of a group-owned ledger

## Context
M2 lets friends share expenses in a group: who paid what, how each expense is divided, who owes
whom, and the payments that clear it. ADR-0004 already put group debts in the same double-entry
ledger as personal money. It left open how an expense and a payment become postings, how an
amount is divided without losing a cent, how the app suggests who should pay whom, and who may
correct a mistake. The M2 issues build on these answers, so they are written down before the code.

## Decision

### One ledger per group
- Each group has its own ledger (`LedgerOwner` of type `GROUP`) and one `GROUP_MEMBER` account
  per member, opened when they join. A member's balance is what the group owes them: positive,
  the others owe them; negative, they owe the others.
- A group has one currency, chosen when it is created (EUR by default) and never changed: its
  balances and payments only make sense in one currency. Groups in other currencies wait for
  multi-currency in M4.
- Which person an account belongs to is kept by `groups` (`group_members`). The ledger still
  knows nothing about people.
- A member can leave only with a balance of zero, so leaving never takes money out of the group.
  Their account and history stay, and they get the same account back if they join again. An
  expense or payment that posts to the account of someone who left can no longer be edited or
  reversed: that would change the balance of a person who is not in the group.

### Posting rules
| Movement | Postings |
|---|---|
| Expense of `T`, paid by P | P `+T`; each person in the split `−share`, the shares adding up to `T` |
| Payment of `X` from A to B (a settlement) | A `+X`, B `−X` |

- When the payer is also in the split, their account gets two postings in the same entry, `+T`
  and `−share`. They are kept apart rather than netted, so the entry itself shows what the payer
  paid and what their part was. The ledger allows it: no rule or constraint asks for different
  accounts, and the balance projection adds up an entry's postings per account first. #83 adds a
  test that keeps it that way.
- A share of zero (two cents split three ways gives 1, 1 and 0) gets no posting, since the ledger
  refuses zero amounts; the split still records it.
- A payment moves debt between two members: the payer owes less and the receiver is owed less.
  Any amount above zero can be recorded, not only a suggested one.

**The balances of a group always add up to zero.** A group's entries post only to its own
members' accounts (the ledger refuses an account from another ledger), and each entry adds up to
zero, so the sum of the member balances is the sum of all those postings: zero. What the creditors
are owed is exactly what the debtors owe, so payments between members can always clear everything.
jqwik properties check it over random sequences of expenses, edits and payments (#88, #89).

### Splits
- An expense is divided **equally** among the chosen members, by **shares** (whole numbers above
  zero: 2 for a couple, 1 for someone alone), by **percentages**, or by **exact amounts**.
- The first three are one calculation with different whole-number weights `w`: 1 for each person,
  their shares, or their percentage in basis points (33.33 % is 3 333, and they add up to 10 000).
  With `W` the sum of the weights, each person gets `⌊T × w / W⌋`, and the cents left over, fewer
  than the number of people, go one each to the largest remainders (the largest-remainder method).
  Exact amounts are used as they are and must add up to `T`.
- Ties between equal remainders go to the member whose id comes first as text. Not
  `UUID.compareTo`: it compares the two halves of the id as signed numbers, so an id starting
  with `8` or more sorts before one starting with `0`, while TypeScript and Postgres compare text.
- Only whole numbers, no `BigDecimal` and no floating point. The frontend previews the split while
  the person types, with `BigInt`, and must show the same cents as the backend; that is simple with
  integers on both sides, and would mean copying `BigDecimal`'s rounding otherwise. Java never forms
  `T × w`: with `T = q × W + r`, a share is `q × w + ⌊r × w / W⌋`. The weights add up to at most
  10 000, so nothing overflows a `long` for any amount the API accepts (up to 2⁵³ − 1).
- So the shares always add up to `T`, each is less than one minor unit (a cent, in EUR) from its
  exact proportion, and
  the same members and inputs always give the same split, in whatever order they come. A JSON
  file of test cases runs in both the backend's and the frontend's tests (#84).
- `groups` stores each person's method, input and share next to the entry (`expense_splits`):
  the inputs cannot be read back from the amounts, since 1 share of 3 and 33.33 % can give the
  same cents.

### Settle-up suggestions
- The member who owes the most pays the member who is owed the most, as much as clears one of
  them, until every balance is zero. Ties go by member id, so the same balances always give the
  same suggestions and a reload never reorders them.
- Each payment brings at least one balance to zero, and the last one two, so there are at most
  `n − 1` payments for `n` members with a balance. Only someone who owes pays, only someone who is
  owed receives, and nobody receives more than they are owed.
- It is not always the fewest payments. With A +7, B +5, C −5, D −4 and E −3, it suggests C pays
  A 5, D pays B 4, E pays A 2 and E pays B 1: four payments, where three would do (C pays B 5,
  D pays A 4, E pays A 3). Finding the fewest means splitting the members into as many groups
  that add up to zero as possible, which is NP-hard. The greedy answer is simple, deterministic,
  and never more than `n − 1`.
- Suggestions are worked out from the balances on every request and never stored. Recording one
  is a payment like any other.

### Corrections
- Expenses and payments are corrected like personal transactions: the entry is reversed and the
  correction posted in the same database transaction (ADR-0004), and the correction's split is
  stored with it. Nothing is updated or deleted.
- Any member can edit or reverse an expense or a payment, not only who recorded it: in a shared
  house or on a trip, whoever spots the mistake fixes it. Each version records who made it, and
  the trail shows every version, so nothing is lost and everyone sees who changed what.
- An archived group takes no new expenses, payments or corrections.

## Consequences
- Groups get the ledger's guarantees as they are: balanced, append-only entries, ordered locking
  when members record expenses at the same time, and retries made safe by `Idempotency-Key`.
- The ledger only gains the `GROUP_MEMBER` kind (#83). Splits, membership and suggestions belong
  to `groups`.
- A group's balances come straight from `account_balances`, with no query of their own.
- Reports and the transactions list stay personal: they read the user's own ledger. A person's
  spending in groups reaches their reports only through the optional mirror (#94), which needs an
  ADR of its own.
- Considered and rejected:
  - **Netting the payer's postings** (+20 instead of +30 and −10): one posting fewer, but the
    entry would no longer show what was paid, and the detail screen would need the split to
    explain the amounts.
  - **A table of debts between pairs of members**, updated on every expense: a second copy of the
    numbers that can drift from the ledger, with a row per pair to keep right. Each member's
    balance already holds everything settle-up needs.
  - **`BigDecimal` for percentages**: the money rules allow it, but the frontend's preview would
    have to copy its rounding exactly.
  - **The fewest payments, by trying every combination**: exact for a few friends, but more code
    and tests to save a payment now and then.
  - **Only whoever recorded an expense may change it**: a mistake would wait for them, and the
    trail already shows who did what.
