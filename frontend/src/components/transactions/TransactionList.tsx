import { Plus, Search } from 'lucide-react'
import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useLocation } from 'react-router'
import type {
  MonthSummary,
  Transaction,
  TransactionFilters,
} from '@/api/transactions'
import { useTransactions } from '@/api/transactions'
import { useNewTransaction } from '@/components/newTransaction'
import { LoadFailed, Loading } from '@/components/QueryState'
import { useLedgerNames } from '@/components/transactions/useLedgerNames'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { CURRENCY } from '@/lib/accounts'
import {
  addDays,
  type Day,
  formatDay,
  formatMonthName,
  formatWeekday,
  today,
} from '@/lib/dates'
import { apiErrorMessage } from '@/lib/forms'
import { formatMoney, type Money } from '@/lib/money'
import { byDay, formatTransactionAmount, netOf } from '@/lib/transactions'
import { cn } from '@/lib/utils'

/**
 * The month's transactions for `filters`, as in the design canvas: the month's count and totals,
 * then each day with its net and its transactions, and "Load more" for the next page.
 */
export function TransactionList({
  filters,
  filtered,
  onClearFilters,
}: {
  filters: TransactionFilters
  /** Whether a filter narrows the month: an empty list then says "No transactions match". */
  filtered: boolean
  onClearFilters: () => void
}) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  const list = useTransactions(filters)
  const names = useLedgerNames()
  const titleId = useId()

  // A failed "Load more" keeps the pages already loaded: only a list with nothing to show fails.
  if (list.data === undefined || names.isPending || names.isError) {
    if (!list.isError && !names.isError) {
      return <Loading>{t('transactions.loading')}</Loading>
    }
    return (
      <LoadFailed
        onRetry={() => {
          void list.refetch()
          names.refetch()
        }}
      >
        {t('transactions.loadFailed')}
      </LoadFailed>
    )
  }

  const { month, summary } = list.data.pages[0]
  const transactions = list.data.pages.flatMap((page) => page.items)
  const days = byDay(transactions)
  const monthName = formatMonthName(month, language)

  return (
    // Dimmed while another month or filter loads, with the list it replaces still readable.
    <div
      aria-busy={list.isPlaceholderData}
      className={cn(
        'flex flex-col gap-3 transition-opacity',
        list.isPlaceholderData && 'opacity-60',
      )}
    >
      <Summary summary={summary} monthName={monthName} />
      <section
        aria-labelledby={titleId}
        className="overflow-hidden rounded-[14px] border bg-card"
      >
        <h2 id={titleId} className="sr-only">
          {t('transactions.listTitle', { month: monthName })}
        </h2>
        {transactions.length === 0 ? (
          filtered ? (
            <NoMatch onClear={onClearFilters} />
          ) : (
            <NothingYet monthName={monthName} />
          )
        ) : (
          <>
            {/* Column titles for sighted people on wide screens; each row reads in full. */}
            <div
              aria-hidden
              className="hidden grid-cols-[2rem_minmax(0,1.6fr)_minmax(0,1fr)_minmax(0,1.2fr)_7rem] gap-x-4 border-b px-5 py-2.5 text-xs font-medium text-muted-foreground sm:grid"
            >
              <span />
              <span>{t('transactions.columns.description')}</span>
              <span>{t('transactions.columns.category')}</span>
              <span>{t('transactions.columns.account')}</span>
              <span className="text-right">
                {t('transactions.columns.amount')}
              </span>
            </div>
            {days.map((group, index) => (
              <DayGroup
                key={group.day}
                day={group.day}
                transactions={group.transactions}
                // The last day of a page may go on in the next one: its net would be partial.
                complete={!(list.hasNextPage && index === days.length - 1)}
                labels={names.labels}
              />
            ))}
          </>
        )}
        {transactions.length > 0 && (
          <div className="flex flex-wrap items-center justify-between gap-3 border-t bg-muted/40 px-5 py-3">
            <span className="text-[13px] text-muted-foreground">
              {t('transactions.showing', {
                shown: transactions.length,
                count: summary.count,
              })}
            </span>
            {list.hasNextPage && (
              <Button
                variant="outline"
                size="lg"
                disabled={list.isFetchingNextPage}
                onClick={() => void list.fetchNextPage()}
              >
                {list.isFetchingNextPage
                  ? t('transactions.loadingMore')
                  : t('transactions.loadMore')}
              </Button>
            )}
            {list.isFetchNextPageError && (
              <p role="alert" className="w-full text-[13px] text-destructive">
                {t('transactions.loadMoreFailed')}{' '}
                {apiErrorMessage(list.error, t)}
              </p>
            )}
          </div>
        )}
      </section>
    </div>
  )
}

/** "41 transactions in October · Income +€1,874.99 · Spending −€1,599.80 · Net +€275.19". */
function Summary({
  summary,
  monthName,
}: {
  summary: MonthSummary
  monthName: string
}) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  const spending: Money = {
    ...summary.spending,
    amountMinor: -summary.spending.amountMinor,
  }
  return (
    <p className="flex flex-wrap gap-x-5 gap-y-1 text-[13px] text-muted-foreground">
      <span>
        {t('transactions.summary.count', {
          count: summary.count,
          month: monthName,
        })}
      </span>
      <span>
        {t('transactions.summary.income')}{' '}
        <Amount money={summary.income} language={language} />
      </span>
      <span>
        {t('transactions.summary.spending')}{' '}
        <Amount money={spending} language={language} />
      </span>
      <span>
        {t('transactions.summary.net')}{' '}
        <Amount money={summary.net} language={language} />
      </span>
    </p>
  )
}

/** An amount with its sign, teal when money came in. */
function Amount({ money, language }: { money: Money; language: string }) {
  return (
    <span
      className={cn(
        'font-medium text-foreground tabular-nums',
        money.amountMinor > 0 && 'text-income-text',
      )}
    >
      {formatMoney(money, language, { signed: true })}
    </span>
  )
}

/** "Today", "Yesterday", or the day of the week, then the date. */
function dayHeading(
  day: Day,
  language: string,
  words: { today: string; yesterday: string },
) {
  const now = today()
  const relative =
    day === now
      ? words.today
      : day === addDays(now, -1)
        ? words.yesterday
        : undefined
  return relative === undefined
    ? {
        label: formatWeekday(day, language),
        date: formatDay(day, language, { day: 'numeric', month: 'long' }),
      }
    : {
        label: relative,
        date: formatDay(day, language, {
          weekday: 'long',
          day: 'numeric',
          month: 'long',
        }),
      }
}

function DayGroup({
  day,
  transactions,
  complete,
  labels,
}: {
  day: Day
  transactions: Transaction[]
  complete: boolean
  labels: ReturnType<typeof useLedgerNames>['labels']
}) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  const { label, date } = dayHeading(day, language, {
    today: t('transactions.today'),
    yesterday: t('transactions.yesterday'),
  })
  const counted = transactions.some(
    (transaction) => transaction.type !== 'TRANSFER',
  )
  const net = netOf(transactions, CURRENCY)

  return (
    <section aria-label={`${label}, ${date}`}>
      <div className="flex items-baseline justify-between gap-3 px-5 pt-3.5 pb-1">
        <h3 className="text-[13px] font-semibold">
          {label}{' '}
          <span className="font-normal text-muted-foreground">{date}</span>
        </h3>
        {/* A day of transfers only has no net: they are neither spending nor income. */}
        {complete && counted && (
          <span className="text-[13px] text-muted-foreground tabular-nums">
            <span className="sr-only">{t('transactions.dayNet')}: </span>
            {formatMoney(net, language, { signed: true })}
          </span>
        )}
      </div>
      <ul className="px-2 pb-1.5">
        {transactions.map((transaction) => (
          <TransactionRow
            key={transaction.id}
            transaction={transaction}
            labels={labels(transaction)}
          />
        ))}
      </ul>
    </section>
  )
}

function TransactionRow({
  transaction,
  labels,
}: {
  transaction: Transaction
  labels: ReturnType<ReturnType<typeof useLedgerNames>['labels']>
}) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  const { search } = useLocation()
  const Icon = labels.icon

  return (
    <li>
      {/* Two lines on a phone (description, then category · account), one row of columns on wider
          screens. The same elements move, rather than being drawn twice, so the link reads once. */}
      <Link
        to={{ pathname: transaction.id, search }}
        className="grid grid-cols-[2rem_minmax(0,1fr)_auto] items-center gap-x-3 rounded-[10px] px-3 py-2.5 outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50 sm:grid-cols-[2rem_minmax(0,1.6fr)_minmax(0,1fr)_minmax(0,1.2fr)_7rem] sm:gap-x-4"
      >
        <span className="row-span-2 grid size-8 place-items-center rounded-full bg-muted text-foreground/75 sm:row-span-1">
          <Icon aria-hidden className="size-4" />
        </span>
        <span className="flex min-w-0 items-center gap-2">
          <span className="truncate font-medium">
            {transaction.description}
          </span>
          {transaction.edited && (
            <Badge variant="outline" className="text-foreground/70">
              {t('transactions.edited')}
            </Badge>
          )}
        </span>
        <span className="col-start-2 row-start-2 flex min-w-0 gap-1 text-[13px] text-foreground/70 sm:contents sm:text-sm">
          <span className="truncate">{labels.category}</span>
          <span aria-hidden className="sm:hidden">
            ·
          </span>
          <span className="truncate">{labels.account}</span>
        </span>
        <span
          className={cn(
            'col-start-3 row-span-2 row-start-1 text-right font-medium whitespace-nowrap tabular-nums sm:col-start-auto sm:row-auto',
            transaction.type === 'INCOME' && 'text-income-text',
            transaction.type === 'TRANSFER' && 'text-muted-foreground',
          )}
        >
          {formatTransactionAmount(transaction, language)}
        </span>
      </Link>
    </li>
  )
}

function NoMatch({ onClear }: { onClear: () => void }) {
  const { t } = useTranslation()
  return (
    <div className="flex flex-col items-center gap-1 px-5 py-14 text-center">
      <span className="grid size-10 place-items-center rounded-full bg-muted text-foreground/70">
        <Search aria-hidden className="size-4.5" />
      </span>
      <p className="mt-2 font-semibold">{t('transactions.noMatch.title')}</p>
      <p className="text-muted-foreground">
        {t('transactions.noMatch.description')}
      </p>
      <Button variant="outline" size="lg" className="mt-3" onClick={onClear}>
        {t('transactions.noMatch.clear')}
      </Button>
    </div>
  )
}

function NothingYet({ monthName }: { monthName: string }) {
  const { t } = useTranslation()
  const newTransaction = useNewTransaction()
  return (
    <div className="flex flex-col items-center gap-1 px-5 py-14 text-center">
      <p className="font-semibold">
        {t('transactions.empty.title', { month: monthName })}
      </p>
      <p className="max-w-sm text-muted-foreground">
        {t('transactions.empty.description')}
      </p>
      <Button
        size="lg"
        className="mt-3"
        onClick={(event) => newTransaction(event.currentTarget)}
      >
        <Plus aria-hidden />
        {t('transactions.new')}
      </Button>
    </div>
  )
}
