import {
  useInfiniteQuery,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import { api } from './client'
import { groupKey, groupsKey } from './groups'
import { useMoneyMutation } from './moneyMutation'
import { dataOrThrow } from './problem'
import type { components } from './schema'

export type GroupEntry = components['schemas']['GroupEntry']
export type GroupEntryPage = components['schemas']['GroupEntryPage']
export type GroupExpense = components['schemas']['GroupExpense']
export type GroupExpenseCategory = components['schemas']['GroupExpenseCategory']
export type GroupExpenseDetail = components['schemas']['GroupExpenseDetail']
export type GroupTrailStep = components['schemas']['GroupTrailStep']
export type NewGroupExpense = components['schemas']['NewGroupExpense']
export type Settlement = components['schemas']['Settlement']

/** The API's default page size: a weekend away fits in one page, and Load more fetches the rest. */
export const ENTRIES_PAGE_SIZE = 50

function entriesKey(groupId: string) {
  return [...groupKey(groupId), 'entries'] as const
}

function expenseKey(groupId: string, expenseId: string) {
  return [...groupKey(groupId), 'expenses', expenseId] as const
}

/** The group's expenses and payments, newest first, a page at a time. */
export function useGroupEntries(groupId: string) {
  return useInfiniteQuery({
    queryKey: entriesKey(groupId),
    queryFn: async ({ pageParam }) =>
      dataOrThrow(
        await api.GET('/v1/groups/{id}/expenses', {
          params: {
            path: { id: groupId },
            query: { cursor: pageParam, limit: ENTRIES_PAGE_SIZE },
          },
        }),
      ),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor,
  })
}

/** An expense with its split and its trail of reversals and corrections. */
export function useGroupExpense(groupId: string, expenseId: string) {
  return useQuery({
    queryKey: expenseKey(groupId, expenseId),
    queryFn: async () =>
      dataOrThrow(
        await api.GET('/v1/groups/{id}/expenses/{expenseId}', {
          params: { path: { id: groupId, expenseId } },
        }),
      ),
  })
}

/**
 * After an expense moved the group's balances: everything under the group (its list, its expenses,
 * and the balances and activity of #93) and the groups list, which shows the person's balance in
 * each. Waited for, so the list is up to date when the dialog closes.
 */
function useRefetchGroup(groupId: string) {
  const queryClient = useQueryClient()
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: groupKey(groupId) }),
      queryClient.invalidateQueries({ queryKey: groupsKey, exact: true }),
    ])
}

/**
 * Records an expense with `idempotencyKey` on every attempt: the dialog keeps one key while it is
 * open, so a double tap or a retry records it once.
 */
export function useCreateGroupExpense(groupId: string, idempotencyKey: string) {
  const refetch = useRefetchGroup(groupId)
  return useMoneyMutation({
    idempotencyKey,
    mutationFn: async (expense: NewGroupExpense, key: string) =>
      dataOrThrow(
        await api.POST('/v1/groups/{id}/expenses', {
          params: {
            path: { id: groupId },
            header: { 'Idempotency-Key': key },
          },
          body: expense,
        }),
      ),
    onSuccess: refetch,
  })
}

/**
 * Reverses an expense, or edits it: with a `replacement`, the API reverses it and records the
 * correction in one request, so an edit never stops halfway with the expense gone. Answers the
 * correction's detail after an edit, the reversed expense's otherwise.
 */
export function useReverseGroupExpense(
  groupId: string,
  idempotencyKey: string,
) {
  const queryClient = useQueryClient()
  const refetch = useRefetchGroup(groupId)
  return useMoneyMutation({
    idempotencyKey,
    mutationFn: async (
      {
        expenseId,
        replacement,
      }: { expenseId: string; replacement?: NewGroupExpense },
      key: string,
    ) =>
      dataOrThrow(
        await api.POST('/v1/groups/{id}/expenses/{expenseId}/reverse', {
          params: {
            path: { id: groupId, expenseId },
            header: { 'Idempotency-Key': key },
          },
          body: replacement === undefined ? {} : { replacement },
        }),
      ),
    onSuccess: async (detail) => {
      queryClient.setQueryData(expenseKey(groupId, detail.expense.id), detail)
      await refetch()
    },
  })
}
