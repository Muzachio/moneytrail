import {
  keepPreviousData,
  useInfiniteQuery,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import { accountsKey } from './accounts'
import { api } from './client'
import { useMoneyMutation } from './moneyMutation'
import { dataOrThrow } from './problem'
import type { components } from './schema'

export type Transaction = components['schemas']['Transaction']
export type TransactionType = components['schemas']['TransactionType']
export type NewTransaction = components['schemas']['NewTransaction']
export type TransactionDetail = components['schemas']['TransactionDetail']
export type TransactionPage = components['schemas']['TransactionPage']
export type MonthSummary = components['schemas']['MonthSummary']
export type Posting = components['schemas']['Posting']
export type TrailStep = components['schemas']['TrailStep']

/** What the transactions page shows: a month, and the filters that narrow it. */
export type TransactionFilters = {
  month: string
  type?: TransactionType
  accountId?: string
  categoryId?: string
  q?: string
}

/** Lists and details alike: anything that moves money changes both. */
export const transactionsKey = ['transactions'] as const

export function transactionKey(id: string) {
  return [...transactionsKey, 'detail', id] as const
}

/** The API's default page size: most months fit in one page, and Load more fetches the rest. */
export const PAGE_SIZE = 50

/**
 * A month's transactions for `filters`, newest first, a page at a time, with the month's totals.
 *
 * While another month or filter loads, the previous list stays on screen, dimmed by the page,
 * rather than giving way to "Loading" at every letter typed in the search.
 */
export function useTransactions(filters: TransactionFilters) {
  return useInfiniteQuery({
    queryKey: [...transactionsKey, 'list', filters],
    queryFn: async ({ pageParam }) =>
      dataOrThrow(
        await api.GET('/v1/transactions', {
          params: {
            query: { ...filters, cursor: pageParam, limit: PAGE_SIZE },
          },
        }),
      ),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor,
    placeholderData: keepPreviousData,
  })
}

/** A transaction with its ledger postings and its trail of reversals and corrections. */
export function useTransaction(id: string) {
  return useQuery({
    queryKey: transactionKey(id),
    queryFn: async () =>
      dataOrThrow(
        await api.GET('/v1/transactions/{id}', { params: { path: { id } } }),
      ),
  })
}

/**
 * After money moved: the transactions (lists, totals, trails) and the accounts (balances, this
 * month's change) are fetched again. Waited for, so the list is up to date when a dialog closes.
 */
function useRefetchAfterMoneyMoved() {
  const queryClient = useQueryClient()
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: transactionsKey }),
      queryClient.invalidateQueries({ queryKey: accountsKey }),
    ])
}

/**
 * Records an expense, an income or a transfer, with `idempotencyKey` on every attempt: the dialog
 * keeps one key while it is open, so a double tap or a retry records the transaction once.
 */
export function useCreateTransaction(idempotencyKey: string) {
  const refetch = useRefetchAfterMoneyMoved()
  return useMoneyMutation({
    idempotencyKey,
    mutationFn: async (transaction: NewTransaction, key: string) =>
      dataOrThrow(
        await api.POST('/v1/transactions', {
          params: { header: { 'Idempotency-Key': key } },
          body: transaction,
        }),
      ),
    onSuccess: refetch,
  })
}

/**
 * Reverses a transaction, or edits it: with a `replacement`, the API reverses it and records the
 * correction in one request, so an edit can never stop halfway with the transaction gone.
 *
 * Answers the correction's detail after an edit, the reversed transaction's otherwise.
 */
export function useReverseTransaction(idempotencyKey: string) {
  const queryClient = useQueryClient()
  const refetch = useRefetchAfterMoneyMoved()
  return useMoneyMutation({
    idempotencyKey,
    mutationFn: async (
      { id, replacement }: { id: string; replacement?: NewTransaction },
      key: string,
    ) =>
      dataOrThrow(
        await api.POST('/v1/transactions/{id}/reverse', {
          params: { path: { id }, header: { 'Idempotency-Key': key } },
          body: replacement === undefined ? {} : { replacement },
        }),
      ),
    onSuccess: async (detail) => {
      queryClient.setQueryData(transactionKey(detail.transaction.id), detail)
      await refetch()
    },
  })
}
