import { keepPreviousData, useQuery } from '@tanstack/react-query'
import type { Day, Month } from '@/lib/dates'
import { monthPeriod, trendPeriod } from '@/lib/reports'
import { api } from './client'
import { dataOrThrow } from './problem'
import type { components } from './schema'

export type SpendingReport = components['schemas']['SpendingReport']
export type CategorySpending = components['schemas']['CategorySpending']
export type MonthTotals = components['schemas']['MonthTotals']
export type ReportGrouping = components['schemas']['ReportGrouping']
export type IncomeAllocation = components['schemas']['IncomeAllocation']

/** Every report: recording, reversing or editing a transaction changes them all. */
export const reportsKey = ['reports'] as const

/** How many of the month's latest transactions the summary shows, at most. */
export const RECENT = 6

/** The income and spending from `from` to `to`, both days included, by category or by month. */
async function spendingReport(query: {
  from: Day
  to: Day
  groupBy: ReportGrouping
}) {
  return dataOrThrow(
    await api.GET('/v1/reports/spending', { params: { query } }),
  )
}

/**
 * What the summary shows of `month`, up to `today`: its totals and spending by category, the six
 * months that end with it, and its latest transactions with their count.
 *
 * One query for the three requests, so that the page changes month all at once. While another
 * month loads, the previous one stays on screen, dimmed by the page, whole: its own month, period,
 * totals and transactions, never one month's title over another month's numbers.
 */
export function useMonthSummary(month: Month, today: Day) {
  return useQuery({
    queryKey: [...reportsKey, 'summary', month, today],
    queryFn: async () => {
      const period = monthPeriod(month, today)
      const [byCategory, trend, page, allocation] = await Promise.all([
        spendingReport({ ...period, groupBy: 'category' }),
        spendingReport({ ...trendPeriod(month, today), groupBy: 'month' }),
        api
          .GET('/v1/transactions', {
            params: { query: { month, limit: RECENT } },
          })
          .then(dataOrThrow),
        api
          .GET('/v1/reports/income-allocation', { params: { query: period } })
          .then(dataOrThrow),
      ])
      return {
        month,
        period,
        /** The month's income and spending. */
        totals: { income: byCategory.income, spending: byCategory.spending },
        categories: byCategory.categories ?? [],
        /** The six months' income and spending, and each month's. */
        trend: {
          income: trend.income,
          spending: trend.spending,
          months: trend.months ?? [],
        },
        /** Where the month's income went: spent, invested, saved and left (#125). */
        allocation,
        /** How many transactions the month has, transfers included. */
        count: page.summary.count,
        recent: page.items,
      }
    },
    placeholderData: keepPreviousData,
  })
}
