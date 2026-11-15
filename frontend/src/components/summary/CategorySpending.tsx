import { useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { To } from 'react-router'
import type { CategorySpending } from '@/api/reports'
import {
  CardEmpty,
  CardLink,
  SummaryCard,
} from '@/components/summary/SummaryCard'
import { Button } from '@/components/ui/button'
import { categoryIcon, categoryName } from '@/lib/categories'
import { formatDayRange, formatMonthName, type Month } from '@/lib/dates'
import { formatMoney, formatPercent, type Money, percentOf } from '@/lib/money'
import type { Period } from '@/lib/reports'
import { cn } from '@/lib/utils'

/**
 * The month's spending by category, largest first, as in the design canvas: each with a bar as
 * long as its share of the largest one, its amount, and its share of the month's spending.
 *
 * The list is the chart's text: name, amount and share are written out in each row, and the bars
 * only draw them, so nothing depends on seeing the bars or their colour.
 */
export function CategorySpendingCard({
  categories,
  spending,
  period,
  month,
  transactionsLink,
  className,
}: {
  categories: CategorySpending[]
  spending: Money
  period: Period
  month: Month
  transactionsLink: To
  className?: string
}) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'

  return (
    <SummaryCard
      title={t('summary.categories.title')}
      description={t('summary.categories.period', {
        period: formatDayRange(period.from, period.to, language),
        total: formatMoney(spending, language),
      })}
      action={
        <CardLink to={transactionsLink}>
          {t('summary.categories.seeTransactions')}
        </CardLink>
      }
      className={className}
    >
      {categories.length === 0 ? (
        <CardEmpty>
          {t('summary.categories.empty', {
            month: formatMonthName(month, language),
          })}
        </CardEmpty>
      ) : (
        <CategoryList categories={categories} spending={spending} withShare />
      )}
    </SummaryCard>
  )
}

/** How many categories the phone shows before "See all". */
const TOP = 4

/** The phone's "Top categories", as in the design canvas: the four largest, then all of them. */
export function TopCategoriesCard({
  categories,
  spending,
  month,
}: {
  categories: CategorySpending[]
  spending: Money
  month: Month
}) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  const [all, setAll] = useState(false)
  const listId = useId()

  return (
    <SummaryCard
      title={t('summary.categories.top')}
      className="gap-3 rounded-2xl p-4"
      action={
        categories.length > TOP && (
          <Button
            variant="ghost"
            size="sm"
            className="-my-1 text-[13px]"
            aria-expanded={all}
            aria-controls={listId}
            onClick={() => setAll(!all)}
          >
            {all ? t('summary.categories.seeFewer') : t('summary.seeAll')}
          </Button>
        )
      }
    >
      {categories.length === 0 ? (
        <CardEmpty>
          {t('summary.categories.empty', {
            month: formatMonthName(month, language),
          })}
        </CardEmpty>
      ) : (
        <CategoryList
          id={listId}
          categories={all ? categories : categories.slice(0, TOP)}
          spending={spending}
        />
      )}
    </SummaryCard>
  )
}

function CategoryList({
  id,
  categories,
  spending,
  withShare = false,
}: {
  id?: string
  categories: CategorySpending[]
  spending: Money
  withShare?: boolean
}) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  // The API lists the largest first: the bars are drawn against it.
  const largest = categories[0].spending

  return (
    <ul id={id} className="flex flex-col gap-2.5">
      {categories.map((category) => {
        const Icon = categoryIcon(category)
        const name = categoryName(category, t)
        const share = percentOf(category.spending, spending)
        return (
          <li
            key={category.categoryId}
            // Fixed columns, so that every bar starts and can end at the same places as the others.
            className={cn(
              'grid items-center gap-x-3',
              withShare
                ? 'grid-cols-[1.75rem_7.5rem_minmax(1rem,1fr)_5.75rem_2.75rem]'
                : 'grid-cols-[1.75rem_6rem_minmax(1rem,1fr)_5.75rem] gap-x-2.5',
            )}
          >
            <span className="grid size-7 place-items-center rounded-lg bg-muted text-foreground/75">
              <Icon aria-hidden className="size-4" />
            </span>
            {/* A long name the column cuts short is still whole on hover and for screen readers. */}
            <span title={name} className="truncate font-medium">
              {name}
            </span>
            <span aria-hidden className="h-2">
              <span
                className="block h-2 rounded-r bg-spending"
                // At least a sliver, so that a small category still shows it has spending.
                style={{
                  width: `${Math.max(percentOf(category.spending, largest) ?? 0, 1)}%`,
                }}
              />
            </span>
            <span className="text-right font-medium tabular-nums">
              {formatMoney(category.spending, language)}
            </span>
            {withShare && share !== undefined && (
              <span className="text-right text-[13px] text-muted-foreground tabular-nums">
                <span className="sr-only">, </span>
                {formatPercent(share, language)}
                <span className="sr-only">
                  {' '}
                  {t('summary.categories.ofSpending')}
                </span>
              </span>
            )}
          </li>
        )
      })}
    </ul>
  )
}
