import type { Account } from '@/api/accounts'
import type { Transaction } from '@/api/transactions'
import {
  defaultCategories,
  euros,
  testAccount,
  testTransaction,
} from '@/test/mockApi'

/**
 * The design canvas' October 2026, with invented names and amounts: four accounts, the default
 * categories, and a month of transactions, one of them a transfer and one edited. Tests that use it
 * fix the clock at midday on Sunday 25 October 2026, the canvas' "today".
 */
export function october() {
  const categories = defaultCategories()
  const category = (key: string) =>
    categories.find((category) => category.key === key)!

  const checking = testAccount({
    name: 'Everyday checking',
    balance: euros(218437),
  })
  const emergency = testAccount({
    name: 'Emergency fund',
    type: 'SAVINGS',
    balance: euros(650000),
  })
  const wallet = testAccount({
    name: 'Wallet',
    type: 'CASH',
    balance: euros(4520),
  })
  const card = testAccount({
    name: 'Credit card',
    type: 'CREDIT_CARD',
    balance: euros(-31240),
  })
  const accounts = [checking, emergency, wallet, card]

  const spent = (
    description: string,
    day: number,
    key: string,
    account: Account,
    amountMinor: number,
    extra: Partial<Transaction> = {},
  ) =>
    testTransaction({
      description,
      occurredOn: `2026-10-${String(day).padStart(2, '0')}`,
      recordedAt: `2026-10-${String(day).padStart(2, '0')}T19:00:00Z`,
      categoryId: category(key).id,
      accountId: account.id,
      amount: euros(amountMinor),
      ...extra,
    })

  // Oldest first: the fake lists the last recorded first within a day, as the API does.
  const transactions = [
    spent('Salary', 1, 'salary', checking, 185000, { type: 'INCOME' }),
    spent('Lunch', 20, 'food', wallet, 980),
    spent('Headphones refund', 20, 'refunds', checking, 2499, {
      type: 'INCOME',
    }),
    spent('Dinner out', 22, 'food', card, 2750, { edited: true }),
    spent('Navegante monthly pass', 23, 'transport', checking, 4000),
    testTransaction({
      type: 'TRANSFER',
      description: 'Emergency fund top-up',
      occurredOn: '2026-10-23',
      recordedAt: '2026-10-23T09:00:00Z',
      fromAccountId: checking.id,
      toAccountId: emergency.id,
      amount: euros(20000),
    }),
    spent('Cinema tickets', 24, 'leisure', card, 1500),
    spent('Groceries', 24, 'food', checking, 3872),
    spent('Brunch', 25, 'food', card, 1840),
  ]
  const named = (description: string) =>
    transactions.find((transaction) => transaction.description === description)!

  return {
    accounts,
    categories,
    transactions,
    category,
    named,
    checking,
    emergency,
    wallet,
    card,
  }
}

/** Midday on the canvas' "today", so the date is the same in any time zone the tests run in. */
export const OCTOBER_25 = new Date(2026, 9, 25, 12)
