import { describe, expect, it } from 'vitest'
import {
  axisTicks,
  incomeLessSpending,
  monthPeriod,
  trendPeriod,
} from './reports'

const euros = (amountMinor: number) => ({ amountMinor, currency: 'EUR' })

describe('monthPeriod', () => {
  it('runs from the first day of the current month to today', () => {
    expect(monthPeriod('2026-10', '2026-10-25')).toEqual({
      from: '2026-10-01',
      to: '2026-10-25',
    })
  })

  it('covers the whole of a month that is over', () => {
    expect(monthPeriod('2026-09', '2026-10-25')).toEqual({
      from: '2026-09-01',
      to: '2026-09-30',
    })
    expect(monthPeriod('2028-02', '2028-03-01')).toEqual({
      from: '2028-02-01',
      to: '2028-02-29',
    })
  })
})

describe('trendPeriod', () => {
  it('takes the six months that end with the month, across New Year too', () => {
    expect(trendPeriod('2026-10', '2026-10-25')).toEqual({
      from: '2026-05-01',
      to: '2026-10-25',
    })
    expect(trendPeriod('2027-02', '2027-03-10')).toEqual({
      from: '2026-09-01',
      to: '2027-02-28',
    })
  })
})

describe('incomeLessSpending', () => {
  it('is what was kept, below zero when more went out', () => {
    expect(
      incomeLessSpending({ income: euros(187499), spending: euros(159980) }),
    ).toEqual(euros(27519))
    expect(
      incomeLessSpending({ income: euros(0), spending: euros(2100) }),
    ).toEqual(euros(-2100))
  })
})

describe('axisTicks', () => {
  it('gives zero and three round steps of whole euros that reach the largest amount', () => {
    // The design canvas' six months: up to €2,775.00.
    expect(axisTicks(277500)).toEqual([0, 100000, 200000, 300000])
    expect(axisTicks(300000)).toEqual([0, 100000, 200000, 300000])
    expect(axisTicks(300001)).toEqual([0, 200000, 400000, 600000])
    expect(axisTicks(120000)).toEqual([0, 50000, 100000, 150000])
    expect(axisTicks(1499)).toEqual([0, 500, 1000, 1500])
  })

  it('still has an axis when nothing moved', () => {
    expect(axisTicks(0)).toEqual([0, 100, 200, 300])
  })

  it('steps in whole units of the currency', () => {
    // A currency without decimals, as the yen: its minor unit is the whole one.
    expect(axisTicks(7, 1)).toEqual([0, 5, 10, 15])
  })
})
