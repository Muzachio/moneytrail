# Product spec — MoneyTrail

> *MoneyTrail*: follow the trail of every cent. Each movement leaves an immutable,
> balanced record in the ledger, so you can always see where the money went and who
> owes whom.

## Problem

Tracking personal spending and splitting shared costs with friends usually means two
apps (a budgeting app + Splitwise) and a lot of manual typing. In Portugal every
receipt carries a fiscal QR code with the merchant's tax ID, date, VAT and total, but
no one uses it to log expenses.

## Vision

One app where you:

1. track your own money across accounts (bank, card, cash),
2. split expenses with friends in groups and settle up,
3. log a purchase in two seconds by scanning the receipt's QR code.

These are also the delivery phases, in this order. The first releases focus on the first
one: recording a person's income and expenses through the month. Groups come next. The
receipt QR scan is a future feature (v0.3), not part of the initial phases.

Under the hood it runs on a **double-entry ledger** built the way a payments company
would build it: immutable entries, idempotent writes, safe concurrency.

## Users

| Persona | Needs |
|---|---|
| **Individual** | See where the money goes each month, keep budgets, track balances per account |
| **Group of friends** (trip, shared house, dinners) | Record who paid what, split fairly, know who owes whom, settle with as few payments as possible |

## Features

### MVP (v0.1): personal finance
- Sign in with Google.
- **Accounts:** checking, savings, credit card, cash. Each has one currency (EUR by default).
- **Transactions:** expense, income, transfer between own accounts.
- **Categories:** default set (Food, Transport, Housing, Leisure, Health, ...) plus user-defined.
- **Editing is a reversal:** "edit" or "delete" creates a compensating entry. History is never lost.
- **Dashboard:** spend by category this month, trend over the last 6 months, balance per account.
- Responsive PWA, installable on a phone.

### v0.2: groups
- Create a group, invite friends with a link.
- Shared expense: paid by one member, split **equally / by exact amounts / by percentage / by shares**.
- Group balances per member (who is owed, who owes).
- **Settle up:** suggests the minimum set of payments to clear all debts and records settlements.
- Optional: also record your share as a personal expense in one of your accounts.

### v0.3 (future): automation
- **Receipt QR scan (ATCUD):** scan the Portuguese fiscal QR code and get a prefilled
  transaction (date, total, VAT). The app learns `merchant tax ID → name + category` per user.
- **Recurring transactions** (rent, subscriptions), created by a background worker.
- **Budgets** per category per month, with an email alert at 80% and 100%.
- **CSV import** of bank statements: async job, column mapping, duplicate detection.

### v0.4 (future): depth
- Multi-currency accounts and expenses (ECB reference rates), reports in a base currency.
- Data export (JSON/CSV) and account deletion (GDPR).
- Domain events via transactional outbox; webhooks or notifications built on them.

## Out of scope (for now)
- Live bank connections (Open Banking / PSD2 aggregators): revisit after v0.4.
- Native mobile apps (the PWA covers it).
- Investments, crypto, tax filing.

## Non-functional requirements
- **Correctness first:** every journal entry balances to zero. Balances can always be
  rebuilt from postings. Retried requests never create duplicates.
- **Privacy:** friends' financial data. Minimal personal data, HTTPS everywhere,
  export and delete available, no third-party trackers.
- **Cost:** hosting must stay under ~€10/month.
- **Operability:** structured logs, metrics and traces from day one. One command to run
  everything locally.

## Success criteria
- 5–10 friends use it for at least a month (a real trip or a shared house is ideal).
- Zero ledger inconsistencies: an invariant check job runs nightly and alerts on any imbalance.
- p95 API latency < 150 ms on the main endpoints under a k6 load test.
