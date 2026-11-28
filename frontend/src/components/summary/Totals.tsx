import { type ReactNode, useId } from 'react'
import { useTranslation } from 'react-i18next'
import { formatDayRange, formatMonthName, type Month } from '@/lib/dates'
import type { IncomeAllocation } from '@/api/reports'
import { formatMoney, formatPercent, type Money, percentOf } from '@/lib/money'
import {
  type IncomeSplit,
  incomeSplit,
  incomeLessSpending as netOf,
  type Period,
  type SplitPart,
} from '@/lib/reports'
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

/** The colour of each part of the income, as a Tailwind background class. */
const partColours: Record<SplitPart['key'], string> = {
  spent: 'bg-spending',
  invested: 'bg-invested',
  saved: 'bg-saved',
}

/**
 * The income split into its parts, one segment per part above zero, each as wide as its amount:
 * the amounts are the flex growth, whole numbers, so no rounding decides a segment's width. Only
 * drawn: the rows below say the same in words.
 */
function SplitBar({ parts, height }: { parts: SplitPart[]; height: string }) {
  if (parts.length === 0) {
    return null
  }
  return (
    <div
      aria-hidden
      className={cn('flex gap-0.5 overflow-hidden rounded-full', height)}
    >
      {parts.map((part) => (
        <div
          key={part.key}
          className={cn('basis-0', partColours[part.key])}
          style={{ flexGrow: part.amount.amountMinor }}
        />
      ))}
    </div>
  )
}

/**
 * One row per part of the income: its colour, name, amount and share. A negative part is written
 * in the warning amber, and money taken out of the investments says so. Under saved, a smaller
 * line gives the savings accounts' part of it.
 */
function SplitRows({
  parts,
  savingsAccounts,
}: {
  parts: SplitPart[]
  savingsAccounts: IncomeSplit['savingsAccounts']
}) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  return (
    <ul className="flex flex-col gap-1.5">
      {parts.map((part) => (
        <li key={part.key} className="flex flex-col text-sm">
          <span className="flex items-center gap-2">
            <span
              aria-hidden
              className={cn(
                'size-2.5 flex-none rounded-[3px]',
                partColours[part.key],
              )}
            />
            <span className="min-w-0 flex-1">
              {t(
                part.key === 'invested' && part.negative
                  ? 'summary.split.investedTaken'
                  : `summary.split.${part.key}`,
              )}
            </span>
            <span
              className={cn(
                'font-medium whitespace-nowrap tabular-nums',
                part.negative && 'text-taken-text',
              )}
            >
              {formatMoney(part.amount, language)}
            </span>
            {part.share !== undefined && (
              <span className="w-11 text-right whitespace-nowrap text-muted-foreground tabular-nums">
                {/* The same minus sign (U+2212) as the amounts, so the columns line up. */}
                {part.share < 0
                  ? `−${formatPercent(-part.share, language)}`
                  : formatPercent(part.share, language)}
              </span>
            )}
          </span>
          {part.key === 'saved' && savingsAccounts !== undefined && (
            <span className="pl-4.5 text-xs text-muted-foreground">
              {savingsAccounts.amountMinor < 0
                ? t('summary.split.takenFromSavings', {
                    amount: formatMoney(
                      {
                        ...savingsAccounts,
                        amountMinor: -savingsAccounts.amountMinor,
                      },
                      language,
                    ),
                  })
                : t(
                    part.amount.amountMinor > 0
                      ? 'summary.split.inSavings'
                      : 'summary.split.intoSavings',
                    { amount: formatMoney(savingsAccounts, language) },
                  )}
            </span>
          )}
        </li>
      ))}
    </ul>
  )
}

/**
 * The three figures at the top of the desktop summary: the month's income, its spending with the
 * share of the income it took, and where the income went (#125).
 */
export function StatCards({
  totals,
  allocation,
  period,
  count,
}: {
  totals: IncomeAndSpending
  allocation: IncomeAllocation
  period: Period
  /** How many transactions the month has, transfers included, as the transactions page counts. */
  count: number
}) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  const titleId = useId()
  const { income, spending } = totals
  const net = netOf(totals)
  const spent = percentOf(spending, income)
  const split = incomeSplit(allocation)

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
          net.amountMinor < 0
            ? t('summary.spending.over', {
                amount: formatMoney(abs(net), language),
              })
            : spent === undefined
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
      <section
        aria-labelledby={titleId}
        className="flex min-w-0 flex-col gap-3 rounded-xl border bg-card p-5"
      >
        <h2
          id={titleId}
          className="text-[13px] font-medium text-muted-foreground"
        >
          {t('summary.split.title')}
        </h2>
        <SplitBar parts={split.bar} height="h-2" />
        <SplitRows
          parts={split.parts}
          savingsAccounts={split.savingsAccounts}
        />
      </section>
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
 * The phone's first card: what was spent this month, large, the share of the income it took, and
 * under the split bar the rest of where the income went (#125).
 */
export function SpentCard({
  totals,
  allocation,
  month,
}: {
  totals: IncomeAndSpending
  allocation: IncomeAllocation
  month: Month
}) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  const titleId = useId()
  const { income, spending } = totals
  const net = netOf(totals)
  const spent = percentOf(spending, income)
  const monthName = formatMonthName(month, language)
  const split = incomeSplit(allocation)
  // Spent is the card's large figure; the rows say where the rest went, saved always among them.
  const rest = split.parts.filter((part) => part.key !== 'spent')

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
      <div className="mt-2.5">
        <SplitBar parts={split.bar} height="h-1.5" />
      </div>
      <p className="mt-1.5 flex flex-wrap justify-between gap-x-2 text-[13px] text-muted-foreground">
        <span>
          {spent === undefined
            ? t('summary.spent.noIncome', { month: monthName })
            : t('summary.spent.ofIncome', {
                percent: formatPercent(spent, language),
                income: formatMoney(income, language),
              })}
        </span>
        {net.amountMinor < 0 && (
          <span className="font-medium text-foreground">
            {t('summary.spent.over', {
              amount: formatMoney(abs(net), language),
            })}
          </span>
        )}
      </p>
      <div className="mt-2.5">
        <SplitRows parts={rest} savingsAccounts={split.savingsAccounts} />
      </div>
    </section>
  )
}
