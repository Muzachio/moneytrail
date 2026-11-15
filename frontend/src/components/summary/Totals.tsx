import { type ReactNode, useId } from 'react'
import { useTranslation } from 'react-i18next'
import { formatDayRange, formatMonthName, type Month } from '@/lib/dates'
import { formatMoney, formatPercent, type Money, percentOf } from '@/lib/money'
import { incomeLessSpending as netOf, type Period } from '@/lib/reports'
import { cn } from '@/lib/utils'

/** The month's income and spending, as the spending report sums them. */
export type IncomeAndSpending = { income: Money; spending: Money }

function abs(money: Money): Money {
  return { ...money, amountMinor: Math.abs(money.amountMinor) }
}

/**
 * The share of the income that was spent, as a bar: blue on a lighter blue, full when more was
 * spent than came in. Only drawn: the text beside it says the same in words.
 */
function SpentBar({ percent }: { percent: number }) {
  return (
    <div aria-hidden className="h-1.5 rounded-full bg-spending/20">
      <div
        className="h-1.5 rounded-full bg-spending"
        style={{ width: `${Math.min(percent, 100)}%` }}
      />
    </div>
  )
}

/**
 * The three figures at the top of the desktop summary, as in the design canvas: the month's
 * income, its spending with the share of the income it took, and what was kept.
 */
export function StatCards({
  totals,
  period,
  month,
  current,
  count,
}: {
  totals: IncomeAndSpending
  period: Period
  month: Month
  /** Whether `month` is this month, summed up to today. */
  current: boolean
  /** How many transactions the month has, transfers included, as the transactions page counts. */
  count: number
}) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  const { income, spending } = totals
  const net = netOf(totals)
  const spent = percentOf(spending, income)
  const kept = percentOf(net, income)

  return (
    <div className="grid grid-cols-[repeat(auto-fit,minmax(13rem,1fr))] gap-4">
      <Stat
        title={t('summary.income.title')}
        value={formatMoney(income, language)}
        note={formatDayRange(period.from, period.to, language)}
      />
      <Stat
        title={t('summary.spending.title')}
        value={formatMoney(spending, language)}
        note={[
          spent === undefined
            ? t('summary.spending.noIncome')
            : t('summary.spending.ofIncome', {
                percent: formatPercent(spent, language),
              }),
          t('summary.spending.count', { count }),
        ].join(' · ')}
      >
        {spent !== undefined && (
          <div className="mt-1.5">
            <SpentBar percent={spent} />
          </div>
        )}
      </Stat>
      <Stat
        title={
          current
            ? t('summary.net.title')
            : t('summary.net.titlePast', {
                month: formatMonthName(month, language),
              })
        }
        value={formatMoney(net, language, { signed: true })}
        valueClassName={cn(net.amountMinor > 0 && 'text-income-text')}
        note={
          net.amountMinor < 0
            ? t('summary.net.over', { amount: formatMoney(abs(net), language) })
            : kept === undefined
              ? t('summary.net.nothing')
              : t('summary.net.kept', {
                  percent: formatPercent(kept, language),
                })
        }
      />
    </div>
  )
}

function Stat({
  title,
  value,
  valueClassName,
  note,
  children,
}: {
  title: string
  value: string
  valueClassName?: string
  note: string
  children?: ReactNode
}) {
  const titleId = useId()
  return (
    <section
      aria-labelledby={titleId}
      className="flex min-w-0 flex-col gap-1 rounded-xl border bg-card p-5"
    >
      <h2
        id={titleId}
        className="text-[13px] font-medium text-muted-foreground"
      >
        {title}
      </h2>
      {/* Proportional figures: a big number on its own reads tighter than a column's digits. */}
      <p
        className={cn(
          'text-[1.75rem] leading-tight font-semibold tracking-tight',
          valueClassName,
        )}
      >
        {value}
      </p>
      {children}
      <p className="mt-1 text-[13px] text-muted-foreground">{note}</p>
    </section>
  )
}

/**
 * The phone's first card, as in the design canvas: what was spent this month, the share of the
 * income it took, and what was kept.
 */
export function SpentCard({
  totals,
  month,
}: {
  totals: IncomeAndSpending
  month: Month
}) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  const titleId = useId()
  const { income, spending } = totals
  const net = netOf(totals)
  const spent = percentOf(spending, income)
  const monthName = formatMonthName(month, language)

  return (
    <section
      aria-labelledby={titleId}
      className="flex flex-col gap-1 rounded-2xl border bg-card p-4"
    >
      <h2
        id={titleId}
        className="text-[13px] font-medium text-muted-foreground"
      >
        {t('summary.spent.title', { month: monthName })}
      </h2>
      <p className="text-[2.5rem] leading-[1.15] font-semibold tracking-tight">
        {formatMoney(spending, language)}
      </p>
      {spent !== undefined && (
        <div className="mt-2.5">
          <SpentBar percent={spent} />
        </div>
      )}
      <p className="mt-1.5 flex flex-wrap justify-between gap-x-2 text-[13px] text-muted-foreground">
        <span>
          {spent === undefined
            ? t('summary.spent.noIncome', { month: monthName })
            : t('summary.spent.ofIncome', {
                percent: formatPercent(spent, language),
                income: formatMoney(income, language),
              })}
        </span>
        <span
          className={cn(
            'font-medium',
            net.amountMinor > 0 ? 'text-income-text' : 'text-foreground',
          )}
        >
          {net.amountMinor < 0
            ? t('summary.spent.over', {
                amount: formatMoney(abs(net), language),
              })
            : t('summary.spent.kept', {
                amount: formatMoney(net, language, { signed: true }),
              })}
        </span>
      </p>
    </section>
  )
}
