import { lazy, Suspense, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { MonthTotals } from '@/api/reports'
import { SummaryCard } from '@/components/summary/SummaryCard'
import { formatMonth, formatMonthName } from '@/lib/dates'
import { formatMoney } from '@/lib/money'
import { incomeLessSpending as netOf, TREND_MONTHS } from '@/lib/reports'
import { cn } from '@/lib/utils'

// Recharts is most of the chart's weight: fetched with the first wide summary, never on a phone.
const TrendChart = lazy(() => import('@/components/summary/TrendChart'))

type View = 'chart' | 'table'

const views = ['chart', 'table'] as const satisfies readonly View[]

/**
 * The last six months of income and spending, as in the design canvas: a pair of bars per month,
 * income on the left in teal and spending on the right in blue, and above them the month under
 * the pointer (the month in view otherwise) in words, with its net.
 *
 * Not by colour alone: the line above the chart is its legend, naming each colour in the order of
 * the bars and giving the month's amounts in text. The table says it all in words: shown with
 * "Table", and always there for screen readers, for whom the chart and its legend are hidden.
 */
export function TrendCard({
  months,
  current,
  className,
}: {
  months: MonthTotals[]
  /** Whether the last month is this month, summed up to today. */
  current: boolean
  className?: string
}) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  const [view, setView] = useState<View>('chart')
  const [hovered, setHovered] = useState<number>()

  // The API always sends every month of the period; nothing to draw otherwise.
  if (months.length === 0) {
    return null
  }
  const last = months.length - 1
  const toDate = (index: number) => current && index === last
  // A period across New Year gives each month its year: "December 2026", "January 2027".
  const spansYears =
    months[0].month.slice(0, 4) !== months[last].month.slice(0, 4)
  const rowLabel = (index: number) => {
    const { month } = months[index]
    const name = spansYears
      ? formatMonth(month, language)
      : formatMonthName(month, language)
    return toDate(index)
      ? t('summary.trend.monthToDate', { month: name })
      : name
  }

  return (
    <SummaryCard
      title={t('summary.trend.title', { count: TREND_MONTHS })}
      description={t('summary.trend.description')}
      action={
        <div
          role="group"
          aria-label={t('summary.trend.view')}
          className="inline-flex flex-none gap-0.5 rounded-lg bg-muted p-0.5"
        >
          {views.map((option) => (
            <button
              key={option}
              type="button"
              aria-pressed={view === option}
              onClick={() => setView(option)}
              className={cn(
                'h-7 rounded-md px-2.5 text-[13px] font-medium text-muted-foreground transition-colors outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50',
                view === option && 'bg-background text-foreground shadow-xs',
              )}
            >
              {t(`summary.trend.views.${option}`)}
            </button>
          ))}
        </div>
      }
      className={className}
    >
      {view === 'chart' && (
        <div aria-hidden className="flex flex-col gap-3">
          <Legend
            month={months[hovered ?? last]}
            toDate={toDate(hovered ?? last)}
          />
          {/* The chart's height is kept while it loads, so the card does not jump. */}
          <div className="h-52">
            <Suspense>
              <TrendChart
                months={months}
                hovered={hovered}
                onHover={setHovered}
                language={language}
              />
            </Suspense>
          </div>
        </div>
      )}
      {/* Hidden by its wrapper: a table grows to fit its cells, whatever width sr-only gives it. */}
      <div className={cn(view === 'chart' && 'sr-only')}>
        <table className="w-full text-[13px] tabular-nums">
          <caption className="sr-only">
            {t('summary.trend.caption', { count: TREND_MONTHS })}
          </caption>
          <thead>
            <tr className="border-b text-muted-foreground">
              <th scope="col" className="py-1.5 text-left font-medium">
                {t('summary.trend.month')}
              </th>
              <th scope="col" className="py-1.5 text-right font-medium">
                {t('summary.trend.income')}
              </th>
              <th scope="col" className="py-1.5 text-right font-medium">
                {t('summary.trend.spending')}
              </th>
              <th scope="col" className="py-1.5 text-right font-medium">
                {t('summary.trend.net')}
              </th>
            </tr>
          </thead>
          <tbody>
            {months.map((month, index) => {
              const net = netOf(month)
              return (
                <tr
                  key={month.month}
                  className="border-b border-border/60 last:border-0"
                >
                  <th
                    scope="row"
                    className="py-1.75 text-left font-medium first-letter:uppercase"
                  >
                    {rowLabel(index)}
                  </th>
                  <td className="py-1.75 text-right">
                    {formatMoney(month.income, language)}
                  </td>
                  <td className="py-1.75 text-right">
                    {formatMoney(month.spending, language)}
                  </td>
                  <td
                    className={cn(
                      'py-1.75 text-right',
                      net.amountMinor > 0 && 'text-income-text',
                    )}
                  >
                    {formatMoney(net, language, { signed: true })}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </SummaryCard>
  )
}

/** The chart's legend, with the amounts of one month: "October 2026 · to date ● Income €…". */
function Legend({ month, toDate }: { month: MonthTotals; toDate: boolean }) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  const name = formatMonth(month.month, language)
  const net = netOf(month)

  return (
    <p className="flex flex-wrap items-center gap-x-4 gap-y-1 rounded-[10px] bg-muted/50 px-3 py-2 text-[13px]">
      <span className="font-semibold first-letter:uppercase">
        {toDate ? t('summary.trend.toDate', { month: name }) : name}
      </span>
      <span className="inline-flex items-center gap-1.5 text-foreground/75">
        <span className="size-2 rounded-full bg-income" />
        {t('summary.trend.income')}
        <span className="font-medium text-foreground tabular-nums">
          {formatMoney(month.income, language)}
        </span>
      </span>
      <span className="inline-flex items-center gap-1.5 text-foreground/75">
        <span className="size-2 rounded-full bg-spending" />
        {t('summary.trend.spending')}
        <span className="font-medium text-foreground tabular-nums">
          {formatMoney(month.spending, language)}
        </span>
      </span>
      <span className="inline-flex items-center gap-1.5 text-foreground/75">
        {t('summary.trend.net')}
        <span
          className={cn(
            'font-medium tabular-nums',
            net.amountMinor > 0 ? 'text-income-text' : 'text-foreground',
          )}
        >
          {formatMoney(net, language, { signed: true })}
        </span>
      </span>
    </p>
  )
}
