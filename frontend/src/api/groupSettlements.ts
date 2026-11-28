import {
  useInfiniteQuery,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import { api } from './client'
import { useRefetchGroup } from './groupExpenses'
import { groupKey } from './groups'
import { useMoneyMutation } from './moneyMutation'
import { dataOrThrow } from './problem'
import type { components } from './schema'

export type MemberBalance = components['schemas']['MemberBalance']
export type SuggestedPayment = components['schemas']['SuggestedPayment']
export type NewSettlement = components['schemas']['NewSettlement']
export type SettlementDetail = components['schemas']['SettlementDetail']
export type ActivityItem = components['schemas']['ActivityItem']
export type ActivityKind = components['schemas']['ActivityKind']

/** The API's default page size, as for the group's list. */
export const ACTIVITY_PAGE_SIZE = 50

function settlementKey(groupId: string, settlementId: string) {
  return [...groupKey(groupId), 'settlements', settlementId] as const
}

/**
 * Every current member's balance, in the order they joined: what the group owes them. Under the
 * group's key, so recording or reversing anything in the group refreshes it (`useRefetchGroup`).
 */
export function useGroupBalances(groupId: string) {
  return useQuery({
    queryKey: [...groupKey(groupId), 'balances'],
    queryFn: async () =>
      dataOrThrow(
        await api.GET('/v1/groups/{id}/balances', {
          params: { path: { id: groupId } },
        }),
      ).items,
  })
}

/** The payments that would settle the group up, worked out by the API from the balances. */
export function useSettleUp(groupId: string) {
  return useQuery({
    queryKey: [...groupKey(groupId), 'settle-up'],
    queryFn: async () =>
      dataOrThrow(
        await api.GET('/v1/groups/{id}/settle-up', {
          params: { path: { id: groupId } },
        }),
      ).items,
  })
}

/** A payment with its trail of reversals and corrections. */
export function useSettlement(groupId: string, settlementId: string) {
  return useQuery({
    queryKey: settlementKey(groupId, settlementId),
    queryFn: async () =>
      dataOrThrow(
        await api.GET('/v1/groups/{id}/settlements/{settlementId}', {
          params: { path: { id: groupId, settlementId } },
        }),
      ),
  })
}

/**
 * Records a payment with `idempotencyKey` on every attempt, so a double tap or a retry records it
 * once. Each payment the settle-up dialog offers keeps its own key while the dialog is open.
 */
export function useCreateSettlement(groupId: string, idempotencyKey: string) {
  const refetch = useRefetchGroup(groupId)
  return useMoneyMutation({
    idempotencyKey,
    mutationFn: async (settlement: NewSettlement, key: string) =>
      dataOrThrow(
        await api.POST('/v1/groups/{id}/settlements', {
          params: {
            path: { id: groupId },
            header: { 'Idempotency-Key': key },
          },
          body: settlement,
        }),
      ),
    onSuccess: refetch,
  })
}

/** Reverses a payment: both balances go back to what they were without it. */
export function useReverseSettlement(groupId: string, idempotencyKey: string) {
  const queryClient = useQueryClient()
  const refetch = useRefetchGroup(groupId)
  return useMoneyMutation({
    idempotencyKey,
    mutationFn: async (settlementId: string, key: string) =>
      dataOrThrow(
        await api.POST('/v1/groups/{id}/settlements/{settlementId}/reverse', {
          params: {
            path: { id: groupId, settlementId },
            header: { 'Idempotency-Key': key },
          },
          body: {},
        }),
      ),
    onSuccess: async (detail) => {
      queryClient.setQueryData(
        settlementKey(groupId, detail.settlement.id),
        detail,
      )
      await refetch()
    },
  })
}

/**
 * What happened in the group, newest first, a page at a time. A listener writes each item after
 * the change commits (ADR-0014), so an item can arrive a moment later: the feed asks again whenever
 * the app comes back into view, as well as after anything recorded here.
 */
export function useGroupActivity(groupId: string) {
  return useInfiniteQuery({
    queryKey: [...groupKey(groupId), 'activity'],
    queryFn: async ({ pageParam }) =>
      dataOrThrow(
        await api.GET('/v1/groups/{id}/activity', {
          params: {
            path: { id: groupId },
            query: { cursor: pageParam, limit: ACTIVITY_PAGE_SIZE },
          },
        }),
      ),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor,
    refetchOnWindowFocus: true,
  })
}
