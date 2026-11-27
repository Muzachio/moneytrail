import { describe, expect, it } from 'vitest'
import type { GroupExpense } from '@/api/groupExpenses'
import { asNewGroupExpense, myPart, sameGroupExpense } from './groupExpenses'
import { splitPreview } from './splits'

const ana = '0199f0a2-0000-7000-8000-000000000002'
const rita = '0199f0a1-0000-7000-8000-000000000001'

function expense(overrides: Partial<GroupExpense> = {}): GroupExpense {
  return {
    id: crypto.randomUUID(),
    amount: { amountMinor: 3000, currency: 'EUR' },
    occurredOn: '2026-11-14',
    description: 'Dinner',
    category: 'food',
    paidBy: ana,
    split: {
      method: 'EQUALLY',
      members: [
        {
          userId: rita,
          value: 1,
          share: { amountMinor: 1500, currency: 'EUR' },
        },
        {
          userId: ana,
          value: 1,
          share: { amountMinor: 1500, currency: 'EUR' },
        },
      ],
    },
    recordedBy: ana,
    recordedAt: '2026-11-14T20:00:00Z',
    edited: false,
    reversed: false,
    ...overrides,
  }
}

describe('myPart', () => {
  it('is what the payer lent the others', () => {
    expect(myPart(expense(), ana)).toEqual({
      kind: 'lent',
      amount: { amountMinor: 1500, currency: 'EUR' },
    })
  })

  it('is the share of someone who did not pay', () => {
    expect(myPart(expense(), rita)).toEqual({
      kind: 'share',
      amount: { amountMinor: 1500, currency: 'EUR' },
    })
  })

  it('is nothing for someone outside the split who did not pay', () => {
    expect(myPart(expense(), crypto.randomUUID())).toEqual({ kind: 'none' })
  })
})

describe('sameGroupExpense', () => {
  it('tells an unchanged expense from a changed one, whatever the order of the members', () => {
    const original = asNewGroupExpense(expense())
    const reordered = {
      ...original,
      split: {
        ...original.split,
        members: original.split.members.toReversed(),
      },
    }

    expect(original.split.members).toEqual([{ userId: rita }, { userId: ana }])
    expect(sameGroupExpense(original, reordered)).toBe(true)
    expect(
      sameGroupExpense(original, { ...original, category: 'leisure' }),
    ).toBe(false)
  })
})

describe('splitPreview', () => {
  const rows = [
    { member: rita, taking: true, typed: '' },
    { member: ana, taking: true, typed: '' },
  ]

  it('needs an amount above zero before anything else', () => {
    const preview = splitPreview('0', 'EUR', 'EQUALLY', rows)

    expect(preview.ok).toBe(false)
    expect(!preview.ok && preview.problem).toEqual({ kind: 'amount' })
  })

  it('splits equally among the people ticked, and gives the members to send', () => {
    const preview = splitPreview('10,01', 'EUR', 'EQUALLY', rows)

    expect(preview.ok && preview.members).toEqual([
      { userId: rita },
      { userId: ana },
    ])
    expect(preview.shares.get(rita)).toBe(501n)
    expect(preview.shares.get(ana)).toBe(500n)
  })

  it('says how much is left, or too much, with exact amounts', () => {
    const typed = (a: string, b: string) => [
      { member: rita, taking: true, typed: a },
      { member: ana, taking: true, typed: b },
    ]

    const left = splitPreview('30', 'EUR', 'EXACT_AMOUNTS', typed('10', '5'))
    const over = splitPreview('30', 'EUR', 'EXACT_AMOUNTS', typed('20', '15'))
    const done = splitPreview('30', 'EUR', 'EXACT_AMOUNTS', typed('20', '10'))

    expect(!left.ok && left.problem).toEqual({
      kind: 'amountLeft',
      amountMinor: 1500n,
    })
    expect(!over.ok && over.problem).toEqual({
      kind: 'amountOver',
      amountMinor: 500n,
    })
    expect(done.ok && done.members).toEqual([
      { userId: rita, value: 2000 },
      { userId: ana, value: 1000 },
    ])
  })

  it('refuses shares that add up to more than the API allows', () => {
    const preview = splitPreview('30', 'EUR', 'SHARES', [
      { member: rita, taking: true, typed: '5000' },
      { member: ana, taking: true, typed: '5001' },
    ])

    expect(!preview.ok && preview.problem).toEqual({ kind: 'tooManyShares' })
  })
})
