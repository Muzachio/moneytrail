# ADR-0015: A person's share of group expenses in their personal ledger

- **Status:** Accepted
- **Date:** 2026-11-28
- **Related:** [ADR-0005](0005-events-and-outbox.md), [ADR-0010](0010-synchronous-listeners.md),
  [ADR-0012](0012-groups-on-the-ledger.md), [ADR-0014](0014-activity-feed-through-the-outbox.md)

## Context
A group's expenses live in the group's ledger (ADR-0012), so a person's own summary and reports
never see what they spent in groups: a dinner with friends is missing from their Food, and the €30
they paid for it is missing from their checking account. #94 lets each member choose, group by
group, to have their part recorded in their personal ledger too: the mirror.

Recording only "my share as an expense" would be wrong whenever the person paid for others. Paying
€30 for a dinner whose share is €10 takes €30 out of the bank; €10 of it was spent, and €20 is owed
by the friends until they pay it back. Double entry has a place for that €20: an account of money
owed to the person, whose balance is exactly what the group's ledger already keeps as their balance
in the group.

## Decision

### Turned on per group, by each member
- Off by default. A member turns it on for one group with a **fallback category** and the
  **account they usually pay from**, and turns it off again whenever they like
  (`GET|PUT|DELETE /v1/groups/{id}/mirror`). Nobody else can turn it on for them, and it changes
  nothing for the other members.
- Only for a group in the personal ledger's currency (EUR until M4).
- Leaving the group turns it off, since the person's balance in it is then zero.

### One account per mirrored group
- Turning it on opens an account in the person's ledger: kind `ASSET`, subtype `group`, named
  after the group. **Its balance always equals the person's balance in the group**: positive, the
  friends owe them; negative, they owe the friends. One account that can go either way, as the
  group balance does, rather than a receivable and a payable that money would hop between.
- Only the mirror posts to it. The personal endpoints refuse it in a new transaction
  (`422 account_from_group`); it can be renamed, but it keeps its type, and only the mirror archives
  and restores it.
- Turning the mirror off moves the account's balance back to `Equity:Opening balances` and archives
  it. Turning it on again restores the same account and starts anew.

### Posting rules
For each group entry, the person's postings in it say what it was for them: `P`, what they paid
(the positive postings to their member account), and `S`, their share or what they received (the
negative ones). ADR-0012 keeps the payer's `+T` and `−share` apart, so both can be read back from
the group's ledger; the events stay as they are and the ledger stays the source of the amounts.

| Group entry | Personal entry (`C` category, `A` paying account, `G` group account) |
|---|---|
| Expense | `C +S`, `A −P`, `G +(P − S)` |
| Payment made (a settlement) | `A −P`, `G +P` |
| Payment received | `A +S`, `G −S` |

A posting of zero is left out, and a person with no posting in an entry gets no entry. In every
row `G` moves by `P − S`, which is exactly how much their member account moved: that is why the
two balances stay equal. The examples:
- Paid €30, share €10: Food `+10`, checking `−30`, group `+20`.
- Someone else paid, share €10: Food `+10`, group `−10`. Nothing leaves the bank yet.
- Paid €30 for the others only: checking `−30`, group `+30`, a transfer.
- Paid back €10: checking `−10`, group `+10`, a transfer, never spending.

The share goes to the person's default category with the same key as the expense's category
(#117: their Food for `food`, whatever they renamed it to), or else an active category called by
that known name, or else the fallback category. Reversing and editing work as for personal
transactions: a reversal in the group reverses the personal entry, and an edit replaces it in one
database transaction, keeping the paying account the person had chosen.

The paying account is the usual one at first. The person can change it on any entry where money
left or reached an account (`POST /v1/transactions/{id}/account`): the personal entry is replaced
with the same postings on the other account. Everything else about a mirrored entry is changed in
the group.

### Turned on halfway through
- The mirror starts from the moment it is turned on. It does not record past expenses one by one,
  which would rewrite the spending of months the person already looked at.
- The group account opens with the person's current balance in the group, posted against
  `Equity:Opening balances` like an account's opening balance, so it counts as neither income nor
  spending in any month.
- The ledger reads that balance and the list of group entries it adds up in one statement, so the
  two always agree, and holds off any new posting to the member's account until the mirror is on
  (`LedgerService.snapshot`, a share lock on the account's balance row). Those entries are marked as
  already counted and are never recorded one by one; any later one finds the mirror on.
- A later edit or reversal of one of them changes the group account by the difference, against
  `Equity:Opening balances`, and touches no category and no paying account: the money for it moved
  before the mirror existed.

### Recorded by a listener through the outbox
- `personal` listens to the events of `groups` with `@ApplicationModuleListener` (ADR-0014), in the
  `api` process, after the group's change commits. A failure never fails the group request, which
  matters here: one member's expense writes into the other members' ledgers. It is resubmitted
  within the hour, like the activity feed's.
- `group_mirror_entries` keeps one row per group entry and person, which makes delivery idempotent:
  an entry with a row is never recorded again. Events can arrive out of order after a failure, so
  an edit or a reversal that arrives before the expense marks the expense as handled, and the
  expense, when it comes, adds nothing.
- Nothing runs on a timer: work happens only when a group event arrives, so the mirror never wakes
  the database on its own.

### What the person sees
- Mirrored entries are transactions with a link to their group: an expense of their share (from
  the paying account, or from the group account when someone else paid), or a transfer between the
  paying account and the group account. The personal endpoints refuse to edit or reverse them
  (`422 transaction_from_group`).
- Shares are spending, in the month of the group expense, so the reports and the summary include
  them with no change.
- In the summary's split of the income (#125) the group account is neither investment nor savings
  (`AccountUse.GROUP`). "Saved" is what remains of the income, which includes it: what the friends
  owe the person is still theirs, and what the person owes them already lowers it.

## Consequences
- `personal` depends on `groups`' events and on `GroupMembership`, which gains the member's account
  in a group. `groups` still knows nothing of personal ledgers.
- The ledger gains one read, `snapshot`. Nothing about posting changes.
- A jqwik property checks over random sequences of expenses, edits, reversals and payments that the
  group account's balance equals the person's balance in the group.
- Considered and rejected:
  - **Only the share, as an expense from the usual account:** simple, but the bank account would
    show €10 leaving when €30 did, and the €20 owed would be nowhere.
  - **Mirroring every past expense when it is turned on:** past months' spending would change, and
    the money for those expenses had already been recorded however the person used to.
  - **A synchronous listener (ADR-0010):** the shares would appear at once, but one member's
    expense would fail on another member's settings, and the group request would wait on every
    member's ledger.
  - **Shares in the events:** the events would grow a field that the group's ledger already holds,
    and every listener would trust a copy instead of the postings.
