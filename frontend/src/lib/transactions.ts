import type {
  NewTransaction,
  Transaction,
  TransactionType,
} from '@/api/transactions'
import type { Day } from '@/lib/dates'
import { formatMoney, type Money, sumMoney } from '@/lib/money'

/** In the order of the page's type filter and the dialog's switch, as in the design canvas. */
export const transactionTypes = [
  'EXPENSE',
  'INCOME',
  'TRANSFER',
] as const satisfies readonly TransactionType[]

export function isTransactionType(text: unknown): text is TransactionType {
  return transactionTypes.some((type) => type === text)
}

/**
 * What a transaction did to the money the person has: an expense takes it out, an income brings
 * it in. A transfer only moves money between their own accounts, so it is kept positive and never
 * counted as either.
 */
export function signedAmount(transaction: Transaction): Money {
  const { amountMinor, currency } = transaction.amount
  return {
    amountMinor: transaction.type === 'EXPENSE' ? -amountMinor : amountMinor,
    currency,
  }
}

/** `−€38.72` for an expense, `+€24.99` for an income, `€200.00` for a transfer. */
export function formatTransactionAmount(
  transaction: Transaction,
  language: string,
) {
  return formatMoney(signedAmount(transaction), language, {
    signed: transaction.type !== 'TRANSFER',
  })
}

/** Income less spending. Transfers add nothing, as in the month's summary from the API. */
export function netOf(transactions: readonly Transaction[], currency: string) {
  return sumMoney(
    currency,
    transactions
      .filter((transaction) => transaction.type !== 'TRANSFER')
      .map(signedAmount),
  )
}

export type TransactionDay = { day: Day; transactions: Transaction[] }

/** `transactions`, newest first as the API lists them, grouped by the day they happened. */
export function byDay(transactions: readonly Transaction[]): TransactionDay[] {
  const days: TransactionDay[] = []
  for (const transaction of transactions) {
    const last = days.at(-1)
    if (last?.day === transaction.occurredOn) {
      last.transactions.push(transaction)
    } else {
      days.push({ day: transaction.occurredOn, transactions: [transaction] })
    }
  }
  return days
}

/** The body that would record `transaction` as it is: an edit starts from it. */
export function asNewTransaction(transaction: Transaction): NewTransaction {
  const { type, amount, occurredOn, description } = transaction
  const common = {
    type,
    amount: { amountMinor: amount.amountMinor, currency: amount.currency },
    occurredOn,
    description,
  }
  return type === 'TRANSFER'
    ? {
        ...common,
        fromAccountId: transaction.fromAccountId,
        toAccountId: transaction.toAccountId,
      }
    : {
        ...common,
        categoryId: transaction.categoryId,
        accountId: transaction.accountId,
      }
}

/**
 * Whether `a` and `b` record the same thing. Saving an edit that changed nothing would still add
 * a reversal and a correction to the trail, so the dialog just closes instead.
 */
export function sameTransaction(a: NewTransaction, b: NewTransaction) {
  return (
    a.type === b.type &&
    a.amount.amountMinor === b.amount.amountMinor &&
    a.amount.currency === b.amount.currency &&
    a.occurredOn === b.occurredOn &&
    a.description === b.description &&
    a.categoryId === b.categoryId &&
    a.accountId === b.accountId &&
    a.fromAccountId === b.fromAccountId &&
    a.toAccountId === b.toAccountId
  )
}

/** "01a16545…6a17": enough of a UUIDv7 to tell entries apart, whose start is only their time. */
export function shortId(id: string) {
  return `${id.slice(0, 8)}…${id.slice(-4)}`
}
