import { ChartColumn, Landmark, Plus } from 'lucide-react'
import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import { useMonthSummary } from '@/api/reports'
import { useNewTransaction } from '@/components/newTransaction'
import { LoadFailed, Loading } from '@/components/QueryState'
import { AccountsCard } from '@/components/summary/AccountsCard'
import {
  CategorySpendingCard,
  TopCategoriesCard,
} from '@/components/summary/CategorySpending'
import { RecentTransactions } from '@/components/summary/RecentTransactions'
import { SpentCard, StatCards } from '@/components/summary/Totals'
import { TrendCard } from '@/components/summary/TrendCard'
import { MonthSwitcher } from '@/components/transactions/TransactionFilters'
import { useLedgerNames } from '@/components/transactions/useLedgerNames'
import { Button } from '@/components/ui/button'
import { formatMonth, isMonth, type Month, monthOf, today } from '@/lib/dates'
import { useWideScreen } from '@/lib/useWideScreen'
import { cn } from '@/lib/utils'

/**
 * The month in the page's address, `/?month=2026-09`, so that a reload or Back shows the same one.
 * The current month is the default and is not written; a month after it has nothing to show.
 */
function useMonth() {
  const [params, setParams] = useSearchParams()
  const latest = monthOf(today())
  const asked = params.get('month') ?? ''
  const month = isMonth(asked) && asked <= latest ? asked : latest
  const change = (next: Month) =>
    // Switching months changes the view, not the place: Back leaves the page.
    setParams(next === latest ? {} : { month: next }, { replace: true })
  return { month, latest, change }
}

/**
 * The Summary page of the design canvas. On a wide screen: the month's income, spending and net,
 * its spending by category, the six-month trend, the latest transactions and the accounts. On a
 * phone, shorter: what was spent and kept, the top categories and the latest transactions.
 */
export function SummaryPage() {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  const wide = useWideScreen()
  const { month, latest, change } = useMonth()

  return (
    <div className="flex flex-col gap-4 sm:gap-6">
      <div className="flex flex-wrap items-center justify-between gap-4 sm:items-end">
        <div>
          <h1 className="font-heading text-[1.75rem] leading-tight font-semibold tracking-tight">
            {t('summary.title')}
          </h1>
          {wide && (
            <p className="mt-1 text-muted-foreground">
              {month === latest
                ? t('summary.lead')
                : t('summary.leadPast', {
                    month: formatMonth(month, language),
                  })}
            </p>
          )}
        </div>
        <MonthSwitcher
          month={month}
          latest={latest}
          onChange={change}
          short={!wide}
        />
      </div>
      <Summary month={month} wide={wide} />
    </div>
  )
}

function Summary({ month, wide }: { month: Month; wide: boolean }) {
  const { t } = useTranslation()
  const day = today()
  const summary = useMonthSummary(month, day)
  const names = useLedgerNames()

  if (summary.data === undefined || names.isPending || names.isError) {
    if (summary.isError || names.isError) {
      return (
        <LoadFailed
          onRetry={() => {
            void summary.refetch()
            names.refetch()
          }}
        >
          {t('summary.loadFailed')}
        </LoadFailed>
      )
    }
    return <Loading>{t('summary.loading')}</Loading>
  }

  // The month of the data on screen: while another month loads, still the one before.
  const { data } = summary
  const shown = data.month
  const current = shown === monthOf(day)
  const accounts = names.accounts.data ?? []
  // Someone who has not recorded anything yet: six months of nothing and an empty month. A month
  // with transfers only is not empty: it has transactions, though they are neither in nor out.
  const nothingYet =
    current &&
    data.count === 0 &&
    data.trend.income.amountMinor === 0 &&
    data.trend.spending.amountMinor === 0
  if (nothingYet) {
    return (
      <div className="flex flex-col gap-4">
        <NothingYet hasAccounts={accounts.length > 0} />
        {wide && accounts.length > 0 && <AccountsCard accounts={accounts} />}
      </div>
    )
  }

  const transactionsLink = {
    pathname: '/transactions',
    search: current ? '' : `?month=${shown}`,
  }
  const updating = summary.isPlaceholderData

  return (
    // Dimmed while another month loads, with the one it replaces still readable.
    <div
      aria-busy={updating}
      className={cn(
        'flex flex-col gap-3 transition-opacity sm:gap-4',
        updating && 'opacity-60',
      )}
    >
      {wide ? (
        <>
          <StatCards
            totals={data.totals}
            allocation={data.allocation}
            period={data.period}
            count={data.count}
          />
          <div className="flex flex-wrap items-stretch gap-4">
            <CategorySpendingCard
              className="flex-[7_1_32.5rem]"
              categories={data.categories}
              spending={data.totals.spending}
              period={data.period}
              month={shown}
              transactionsLink={transactionsLink}
            />
            <TrendCard
              className="flex-[5_1_22.5rem]"
              months={data.trend.months}
              current={current}
            />
          </div>
          <div className="flex flex-wrap items-start gap-4">
            <RecentTransactions
              className="flex-[7_1_32.5rem]"
              transactions={data.recent}
              labels={names.labels}
              month={shown}
              transactionsLink={transactionsLink}
            />
            <AccountsCard className="flex-[5_1_22.5rem]" accounts={accounts} />
          </div>
        </>
      ) : (
        <>
          <SpentCard
            totals={data.totals}
            allocation={data.allocation}
            month={shown}
          />
          <TopCategoriesCard
            categories={data.categories}
            spending={data.totals.spending}
            month={shown}
          />
          <RecentTransactions
            compact
            transactions={data.recent}
            labels={names.labels}
            month={shown}
            transactionsLink={transactionsLink}
          />
        </>
      )}
    </div>
  )
}

/** A first visit: what the page will show, and the first step to get there. */
function NothingYet({ hasAccounts }: { hasAccounts: boolean }) {
  const { t } = useTranslation()
  const newTransaction = useNewTransaction()
  const titleId = useId()

  return (
    <section
      aria-labelledby={titleId}
      className="flex flex-col items-center gap-1 rounded-xl border border-dashed bg-card px-6 py-12 text-center"
    >
      <span className="grid size-10 place-items-center rounded-[10px] bg-muted text-foreground/80">
        <ChartColumn aria-hidden className="size-5" />
      </span>
      <h2 id={titleId} className="mt-2 font-semibold">
        {t('summary.empty.title')}
      </h2>
      <p className="max-w-md text-muted-foreground">
        {hasAccounts
          ? t('summary.empty.description')
          : t('summary.empty.descriptionNoAccounts')}
      </p>
      {hasAccounts ? (
        <Button
          size="lg"
          className="mt-3"
          onClick={(event) => newTransaction(event.currentTarget)}
        >
          <Plus aria-hidden />
          {t('transactions.new')}
        </Button>
      ) : (
        // Every transaction moves money from or to an account: the first one needs an account.
        <Button size="lg" className="mt-3" asChild>
          <Link to="/accounts">
            <Landmark aria-hidden />
            {t('summary.empty.addAccount')}
          </Link>
        </Button>
      )}
    </section>
  )
}
