import { useTranslation } from 'react-i18next'
import { Outlet, useSearchParams } from 'react-router'
import type { TransactionFilters } from '@/api/transactions'
import {
  MonthSwitcher,
  TransactionFilterBar,
} from '@/components/transactions/TransactionFilters'
import { TransactionList } from '@/components/transactions/TransactionList'
import { useLedgerNames } from '@/components/transactions/useLedgerNames'
import { isMonth, monthOf, today } from '@/lib/dates'
import { isTransactionType } from '@/lib/transactions'

/** Where each filter lives in the page's address: `/transactions?month=2026-10&type=EXPENSE`. */
const PARAMS = {
  month: 'month',
  type: 'type',
  accountId: 'account',
  categoryId: 'category',
  q: 'q',
} as const satisfies Record<keyof TransactionFilters, string>

/**
 * The filters in the page's address, so that a reload, the back button after a transaction's
 * detail, or a shared link shows the same list. Anything that is not a filter the API knows is
 * left out rather than sent; the current month is the default and is not written.
 */
function useFilters() {
  const [params, setParams] = useSearchParams()
  const latest = monthOf(today())
  const month = params.get(PARAMS.month) ?? ''
  const type = params.get(PARAMS.type)
  const filters: TransactionFilters = {
    month: isMonth(month) && month <= latest ? month : latest,
    type: isTransactionType(type) ? type : undefined,
    accountId: params.get(PARAMS.accountId) || undefined,
    categoryId: params.get(PARAMS.categoryId) || undefined,
    q: params.get(PARAMS.q)?.trim() || undefined,
  }

  const change = (change: Partial<TransactionFilters>) =>
    setParams(
      (previous) => {
        const next = new URLSearchParams(previous)
        for (const [name, value] of Object.entries(change)) {
          const param = PARAMS[name as keyof TransactionFilters]
          if (value === undefined || (name === 'month' && value === latest)) {
            next.delete(param)
          } else {
            next.set(param, value)
          }
        }
        return next
      },
      // Filtering changes the view, not the place: Back leaves the page rather than undoing each letter.
      { replace: true },
    )

  return { filters, latest, change }
}

/** The Transactions page of the design canvas. A transaction's detail opens over it. */
export function TransactionsPage() {
  const { t } = useTranslation()
  const { filters, latest, change } = useFilters()
  const names = useLedgerNames()
  const filtered =
    filters.type !== undefined ||
    filters.accountId !== undefined ||
    filters.categoryId !== undefined ||
    filters.q !== undefined

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-heading text-[1.75rem] leading-tight font-semibold tracking-tight">
            {t('transactions.title')}
          </h1>
          <p className="mt-1 text-muted-foreground">
            {t('transactions.description')}
          </p>
        </div>
        <MonthSwitcher
          month={filters.month}
          latest={latest}
          onChange={(month) => change({ month })}
        />
      </div>
      <TransactionFilterBar
        filters={filters}
        accounts={names.accounts.data ?? []}
        categories={names.categories.data ?? []}
        onChange={change}
      />
      <TransactionList
        filters={filters}
        filtered={filtered}
        onClearFilters={() =>
          change({
            type: undefined,
            accountId: undefined,
            categoryId: undefined,
            q: undefined,
          })
        }
      />
      <Outlet />
    </div>
  )
}
