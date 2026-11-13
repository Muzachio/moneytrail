import { describe, expect, it } from 'vitest'
import type { Transaction } from '@/api/transactions'
import { euros, testTransaction } from '@/test/mockApi'
import {
  asNewTransaction,
  byDay,
  formatTransactionAmount,
  netOf,
  sameTransaction,
  shortId,
} from './transactions'

function made(
  type: Transaction['type'],
  amountMinor: number,
  occurredOn = '2026-10-24',
) {
  return testTransaction({
    type,
    description: type,
    amount: euros(amountMinor),
    occurredOn,
    ...(type === 'TRANSFER'
      ? { fromAccountId: 'a', toAccountId: 'b' }
      : { categoryId: 'c', accountId: 'a' }),
  })
}

describe('transactions', () => {
  it('signs expenses and income, never transfers', () => {
    expect(formatTransactionAmount(made('EXPENSE', 3872), 'en')).toBe('−€38.72')
    expect(formatTransactionAmount(made('INCOME', 2499), 'en')).toBe('+€24.99')
    expect(formatTransactionAmount(made('TRANSFER', 20000), 'en')).toBe(
      '€200.00',
    )
  })

  it('leaves transfers out of a net', () => {
    expect(
      netOf(
        [made('EXPENSE', 4000), made('TRANSFER', 20000), made('INCOME', 1000)],
        'EUR',
      ),
    ).toEqual(euros(-3000))
    expect(netOf([made('TRANSFER', 20000)], 'EUR')).toEqual(euros(0))
  })

  it('groups the days in the order the API lists them', () => {
    const days = byDay([
      made('EXPENSE', 1, '2026-10-25'),
      made('EXPENSE', 2, '2026-10-24'),
      made('INCOME', 3, '2026-10-24'),
    ])
    expect(
      days.map(({ day, transactions }) => [day, transactions.length]),
    ).toEqual([
      ['2026-10-25', 1],
      ['2026-10-24', 2],
    ])
  })

  it('tells whether an edit changed anything', () => {
    const expense = made('EXPENSE', 3872)
    const body = asNewTransaction(expense)
    expect(body).toEqual({
      type: 'EXPENSE',
      amount: euros(3872),
      occurredOn: '2026-10-24',
      description: 'EXPENSE',
      categoryId: 'c',
      accountId: 'a',
    })
    expect(sameTransaction(body, { ...body })).toBe(true)
    expect(sameTransaction(body, { ...body, amount: euros(3873) })).toBe(false)
    expect(
      sameTransaction(asNewTransaction(made('TRANSFER', 1)), {
        ...asNewTransaction(made('TRANSFER', 1)),
        toAccountId: 'c',
      }),
    ).toBe(false)
  })

  it('shortens an entry id to its start and its end', () => {
    expect(shortId('01a16545-4db0-7a3d-9c41-5e2b8f0d6a17')).toBe(
      '01a16545…6a17',
    )
  })
})
