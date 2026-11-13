import type { Account } from '@/api/accounts'
import type { Category } from '@/api/categories'
import type {
  MonthSummary,
  NewTransaction,
  Posting,
  TrailStep,
  Transaction,
  TransactionDetail,
  TransactionPage,
} from '@/api/transactions'

/** Why the fake refuses a request, as the API's problem `code`. */
export class Refused extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, code: string) {
    super(code)
    this.status = status
    this.code = code
  }
}

type Query = {
  month?: string
  type?: Transaction['type']
  accountId?: string
  categoryId?: string
  q?: string
  cursor?: string
  limit?: number
}

/**
 * The transactions of the in-memory API, with the rules of the real one that the pages rely on:
 * every version is kept, a reversal marks its version `reversed` and adds to the trail, the list
 * leaves reversed versions out, and archived accounts and categories cannot be used by a new
 * transaction, but a correction may keep the ones it replaces.
 */
export function fakeTransactions(
  seed: Transaction[],
  accounts: Account[],
  categories: Category[],
  newId: () => string,
) {
  const versions = seed.map((transaction) => ({ ...transaction }))
  const trails = new Map<string, TrailStep[]>(
    versions.map((transaction) => [
      transaction.id,
      [
        {
          entryId: transaction.id,
          action: 'RECORDED',
          type: transaction.type,
          amount: transaction.amount,
          recordedAt: transaction.recordedAt,
        },
      ],
    ]),
  )

  const trailOf = (id: string) =>
    [...trails.values()].find((trail) =>
      trail.some((step) => step.entryId === id),
    ) ?? []

  const find = (id: string) => {
    const transaction = versions.find((version) => version.id === id)
    if (transaction === undefined) {
      throw new Refused(404, 'transaction_not_found')
    }
    return transaction
  }

  function postings(transaction: Transaction): Posting[] {
    const plus = transaction.amount
    const minus = { ...plus, amountMinor: -plus.amountMinor }
    switch (transaction.type) {
      case 'EXPENSE':
        return [
          { accountId: transaction.categoryId!, amount: plus },
          { accountId: transaction.accountId!, amount: minus },
        ]
      case 'INCOME':
        return [
          { accountId: transaction.categoryId!, amount: minus },
          { accountId: transaction.accountId!, amount: plus },
        ]
      case 'TRANSFER':
        return [
          { accountId: transaction.toAccountId!, amount: plus },
          { accountId: transaction.fromAccountId!, amount: minus },
        ]
    }
  }

  function detail(id: string): TransactionDetail {
    const transaction = find(id)
    const trail = trailOf(id)
    const current = versions.find(
      (version) =>
        !version.reversed && trail.some((step) => step.entryId === version.id),
    )
    return {
      transaction: { ...transaction },
      postings: postings(transaction),
      trail: trail.map((step) => ({ ...step })),
      ...(current && { currentId: current.id }),
    }
  }

  /** Archived accounts and categories may stay only on a correction that already used them. */
  function check(body: NewTransaction, replaced?: Transaction) {
    const kept = new Set(
      replaced === undefined
        ? []
        : [
            replaced.accountId,
            replaced.fromAccountId,
            replaced.toAccountId,
            replaced.categoryId,
          ],
    )
    const accountIds =
      body.type === 'TRANSFER'
        ? [body.fromAccountId, body.toAccountId]
        : [body.accountId]
    for (const id of accountIds) {
      const account = accounts.find((account) => account.id === id)
      if (account === undefined) {
        throw new Refused(422, 'account_not_found')
      }
      if (account.archived && !kept.has(id)) {
        throw new Refused(422, 'account_archived')
      }
    }
    if (body.type !== 'TRANSFER') {
      const category = categories.find(
        (category) => category.id === body.categoryId,
      )
      if (category === undefined) {
        throw new Refused(422, 'category_not_found')
      }
      if (category.kind !== body.type) {
        throw new Refused(422, 'category_kind_mismatch')
      }
      if (category.archived && !kept.has(category.id)) {
        throw new Refused(422, 'category_archived')
      }
    }
  }

  function version(body: NewTransaction, edited: boolean): Transaction {
    return {
      ...body,
      description: body.description.trim(),
      id: newId(),
      recordedAt: new Date().toISOString(),
      edited,
      reversed: false,
    }
  }

  function record(body: NewTransaction): Transaction {
    check(body)
    const transaction = version(body, false)
    versions.push(transaction)
    trails.set(transaction.id, [
      {
        entryId: transaction.id,
        action: 'RECORDED',
        type: transaction.type,
        amount: transaction.amount,
        recordedAt: transaction.recordedAt,
      },
    ])
    return { ...transaction }
  }

  function reverse(id: string, replacement?: NewTransaction) {
    const reversed = find(id)
    if (reversed.reversed) {
      throw new Refused(409, 'transaction_already_reversed')
    }
    if (replacement !== undefined) {
      check(replacement, reversed)
    }
    const trail = trailOf(id)
    reversed.reversed = true
    trail.push({
      entryId: newId(),
      action: 'REVERSED',
      type: reversed.type,
      amount: reversed.amount,
      recordedAt: new Date().toISOString(),
      reversesEntryId: id,
    })
    if (replacement === undefined) {
      return detail(id)
    }
    const correction = version(replacement, true)
    versions.push(correction)
    trail.push({
      entryId: correction.id,
      action: 'CORRECTED',
      type: correction.type,
      amount: correction.amount,
      recordedAt: correction.recordedAt,
    })
    return detail(correction.id)
  }

  function list({
    month = '2026-10',
    type,
    accountId,
    categoryId,
    q,
    cursor,
    limit = 50,
  }: Query): TransactionPage {
    const needle = q?.trim().toLowerCase() ?? ''
    const matching = versions
      .filter(
        (transaction) =>
          !transaction.reversed &&
          transaction.occurredOn.startsWith(month) &&
          (type === undefined || transaction.type === type) &&
          (accountId === undefined ||
            [
              transaction.accountId,
              transaction.fromAccountId,
              transaction.toAccountId,
            ].includes(accountId)) &&
          (categoryId === undefined || transaction.categoryId === categoryId) &&
          transaction.description.toLowerCase().includes(needle),
      )
      .sort(
        (a, b) =>
          b.occurredOn.localeCompare(a.occurredOn) || b.id.localeCompare(a.id),
      )
    const start = cursor === undefined ? 0 : Number(cursor)
    const items = matching.slice(start, start + limit)
    const next = start + limit
    return {
      month,
      summary: summary(matching),
      items: items.map((transaction) => ({ ...transaction })),
      ...(next < matching.length && { nextCursor: String(next) }),
    }
  }

  return { versions, detail, record, reverse, list }
}

function summary(transactions: Transaction[]): MonthSummary {
  const sum = (type: Transaction['type']) =>
    transactions
      .filter((transaction) => transaction.type === type)
      .reduce((total, transaction) => total + transaction.amount.amountMinor, 0)
  const income = sum('INCOME')
  const spending = sum('EXPENSE')
  return {
    count: transactions.length,
    income: { amountMinor: income, currency: 'EUR' },
    spending: { amountMinor: spending, currency: 'EUR' },
    net: { amountMinor: income - spending, currency: 'EUR' },
  }
}
