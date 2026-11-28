import { CURRENCY } from '@/lib/accounts'
import {
  addMonths,
  type Day,
  firstDayOf,
  lastDayOf,
  type Month,
} from '@/lib/dates'
import { type Money, percentOf, sumMoney } from '@/lib/money'

/** How many months the summary's trend shows, the month in view included. */
export const TREND_MONTHS = 6

export type Period = { from: Day; to: Day }

/**
 * The days of `month` that the summary sums up: the whole month once it is over, and the current
 * one up to `today`, so its totals read "1–25 October" and say what happened so far.
 */
export function monthPeriod(month: Month, today: Day): Period {
  const last = lastDayOf(month)
  return { from: firstDayOf(month), to: last < today ? last : today }
}

/** What was kept: income less spending, negative when more went out than came in. */
export function incomeLessSpending({
  income,
  spending,
}: {
  income: Money
  spending: Money
}): Money {
  return sumMoney(CURRENCY, [
    income,
    { ...spending, amountMinor: -spending.amountMinor },
  ])
}

/**
 * The ticks of an axis of amounts up to `max` minor units: zero and three equal steps of 1, 2 or 5
 * times a power of ten of whole units (`unit` minor units each), the smallest that reaches `max`.
 * Up to €2,775.00 they are €0, €1K, €2K and €3K, as in the design canvas.
 *
 * Worked out here rather than by the chart, whose "nice" steps are nice in cents (€0, €950,
 * €1,900): whole numbers throughout, no floats.
 */
export function axisTicks(max: number, unit = 100): number[] {
  for (let power = unit; ; power *= 10) {
    for (const multiple of [1, 2, 5]) {
      const step = multiple * power
      if (step * 3 >= max) {
        return [0, step, step * 2, step * 3]
      }
    }
  }
}

/** One part of where the income went (#125). */
export type SplitPart = {
  key: 'spent' | 'invested' | 'saved'
  amount: Money
  /**
   * Below zero: money taken out of the investments, or saved negative because more was spent or
   * invested than came in.
   */
  negative: boolean
  /**
   * The part's share of the month's income, a whole percentage, negative when the part is;
   * undefined without income to share.
   */
  share: number | undefined
}

export type IncomeSplit = {
  /** Spent and saved always, and invested between them when it is not zero. */
  parts: SplitPart[]
  /** The parts the bar draws: the ones above zero, each as wide as its amount. */
  bar: SplitPart[]
  /**
   * The savings accounts' part of what was saved, for the detail line under it; negative when
   * money was taken out of them, undefined when they did not change.
   */
  savingsAccounts: Money | undefined
}

/**
 * The summary's split of the month's income from the API's allocation, whose three parts add up
 * to the income. Shares are of the income, the same base as the spending card's, so the two never
 * disagree; a negative part has a negative share, and the shares still add up to 100% (or close
 * to it, each being rounded).
 */
export function incomeSplit({
  income,
  spending,
  invested,
  saved,
  savingsAccounts,
}: {
  income: Money
  spending: Money
  invested: Money
  saved: Money
  savingsAccounts: Money
}): IncomeSplit {
  const all: [SplitPart['key'], Money][] = [
    ['spent', spending],
    ['invested', invested],
    ['saved', saved],
  ]
  const parts = all
    .filter(([key, amount]) => key !== 'invested' || amount.amountMinor !== 0)
    .map(([key, amount]): SplitPart => {
      const negative = amount.amountMinor < 0
      const share = percentOf(
        { ...amount, amountMinor: Math.abs(amount.amountMinor) },
        income,
      )
      return {
        key,
        amount,
        negative,
        share: share === undefined || !negative ? share : -share,
      }
    })
  return {
    parts,
    bar: parts.filter((part) => part.amount.amountMinor > 0),
    savingsAccounts:
      savingsAccounts.amountMinor === 0 ? undefined : savingsAccounts,
  }
}

/** The six months that end with `month`, the last of them only up to `today`. */
export function trendPeriod(month: Month, today: Day): Period {
  return {
    from: firstDayOf(addMonths(month, 1 - TREND_MONTHS)),
    to: monthPeriod(month, today).to,
  }
}
