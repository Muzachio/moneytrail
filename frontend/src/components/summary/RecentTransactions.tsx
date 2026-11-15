import type { TFunction } from 'i18next'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import type { Transaction } from '@/api/transactions'
import {
  CardEmpty,
  CardLink,
  SummaryCard,
} from '@/components/summary/SummaryCard'
import type { TransactionLabels } from '@/components/transactions/useLedgerNames'
import { Badge } from '@/components/ui/badge'
import {
  addDays,
  type Day,
  formatDay,
  formatMonthName,
  type Month,
  today,
} from '@/lib/dates'
import { formatTransactionAmount } from '@/lib/transactions'
import { cn } from '@/lib/utils'

/** "Today", "Yesterday", or a short date: "Fri, 23 Oct". */
function relativeDay(day: Day, language: string, t: TFunction) {
  const now = today()
  if (day === now) {
    return t('transactions.today')
  }
  if (day === addDays(now, -1)) {
    return t('transactions.yesterday')
  }
  return formatDay(day, language, {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
  })
}

/**
 * The month's latest transactions, newest first, each opening its detail, as in the design
 * canvas: on a wide screen six, with the category and the day; on a phone three, with the day
 * where the category was.
 */
export function RecentTransactions({
  transactions,
  labels,
  month,
  transactionsLink,
  compact = false,
  className,
}: {
  transactions: Transaction[]
  labels: (transaction: Transaction) => TransactionLabels
  month: Month
  /** The transactions page, at `month`. */
  transactionsLink: { pathname: string; search: string }
  /** The phone's card: fewer rows and the shorter title. */
  compact?: boolean
  className?: string
}) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  const shown = transactions.slice(0, compact ? 3 : 6)

  return (
    <SummaryCard
      title={
        compact ? t('summary.recent.titleShort') : t('summary.recent.title')
      }
      action={
        <CardLink to={transactionsLink}>
          {compact ? t('summary.seeAll') : t('summary.recent.viewAll')}
        </CardLink>
      }
      className={cn(
        compact ? 'gap-1 rounded-2xl p-4 pb-2' : 'gap-2 pb-3',
        className,
      )}
    >
      {shown.length === 0 ? (
        <CardEmpty>
          {t('summary.recent.empty', {
            month: formatMonthName(month, language),
          })}
        </CardEmpty>
      ) : (
        <ul className={cn(!compact && '-mx-2')}>
          {shown.map((transaction) => {
            const label = labels(transaction)
            const Icon = label.icon
            const day = relativeDay(transaction.occurredOn, language, t)
            return (
              <li key={transaction.id}>
                <Link
                  // The detail opens over the transactions page, at the transaction's month.
                  to={{
                    pathname: `/transactions/${transaction.id}`,
                    search: transactionsLink.search,
                  }}
                  className={cn(
                    'grid grid-cols-[auto_minmax(0,1fr)_auto] items-center gap-x-3 outline-none focus-visible:ring-3 focus-visible:ring-ring/50',
                    compact
                      ? 'min-h-13 rounded-lg py-2'
                      : 'rounded-[10px] px-2 py-2.5 hover:bg-muted',
                  )}
                >
                  <span
                    className={cn(
                      'grid place-items-center rounded-full bg-muted text-foreground/75',
                      compact ? 'size-9' : 'size-8',
                    )}
                  >
                    <Icon aria-hidden className="size-4" />
                  </span>
                  <span className="min-w-0">
                    <span className="flex min-w-0 items-center gap-2">
                      <span className="truncate font-medium">
                        {transaction.description}
                      </span>
                      {transaction.edited && !compact && (
                        <Badge variant="outline" className="text-foreground/70">
                          {t('transactions.edited')}
                        </Badge>
                      )}
                    </span>
                    <span className="block truncate text-[13px] text-muted-foreground">
                      {compact ? day : label.category}
                      {' · '}
                      {label.account}
                    </span>
                  </span>
                  <span className="text-right">
                    <span
                      className={cn(
                        'block font-medium whitespace-nowrap tabular-nums',
                        transaction.type === 'INCOME' && 'text-income-text',
                        transaction.type === 'TRANSFER' &&
                          'text-muted-foreground',
                      )}
                    >
                      {formatTransactionAmount(transaction, language)}
                    </span>
                    {!compact && (
                      <span className="block text-[13px] text-muted-foreground">
                        {day}
                      </span>
                    )}
                  </span>
                </Link>
              </li>
            )
          })}
        </ul>
      )}
    </SummaryCard>
  )
}
