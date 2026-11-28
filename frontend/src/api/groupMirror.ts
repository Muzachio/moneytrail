import { useQuery, useQueryClient } from '@tanstack/react-query'
import { accountsKey } from './accounts'
import { api } from './client'
import { groupKey } from './groups'
import { useMoneyMutation } from './moneyMutation'
import { dataOrThrow } from './problem'
import { reportsKey } from './reports'
import type { components } from './schema'
import { transactionsKey } from './transactions'

export type GroupMirror = components['schemas']['GroupMirror']
export type GroupMirrorSettings = components['schemas']['GroupMirrorSettings']

function groupMirrorKey(groupId: string) {
  return [...groupKey(groupId), 'mirror'] as const
}

/**
 * Whether the signed-in person records their share of the group in their own ledger
 * (docs/adr/0015-group-shares-in-the-personal-ledger.md). Off until they turn it on.
 */
export function useGroupMirror(groupId: string) {
  return useQuery({
    queryKey: groupMirrorKey(groupId),
    queryFn: async () =>
      dataOrThrow(
        await api.GET('/v1/groups/{id}/mirror', {
          params: { path: { id: groupId } },
        }),
      ),
  })
}

/**
 * Turning the mirror on or off moves money in the person's ledger (the group's account opens with
 * their balance, or gives it back), so both take an Idempotency-Key and refresh what shows money.
 */
function useRefetchMirror(groupId: string) {
  const queryClient = useQueryClient()
  return (mirror: GroupMirror) => {
    queryClient.setQueryData(groupMirrorKey(groupId), mirror)
    return Promise.all([
      queryClient.invalidateQueries({ queryKey: accountsKey }),
      queryClient.invalidateQueries({ queryKey: transactionsKey }),
      queryClient.invalidateQueries({ queryKey: reportsKey }),
    ])
  }
}

/** Turns the mirror on, or changes its category and account when it is on. */
export function useTurnOnGroupMirror(groupId: string) {
  const refetch = useRefetchMirror(groupId)
  return useMoneyMutation({
    mutationFn: async (settings: GroupMirrorSettings, key: string) =>
      dataOrThrow(
        await api.PUT('/v1/groups/{id}/mirror', {
          params: { path: { id: groupId }, header: { 'Idempotency-Key': key } },
          body: settings,
        }),
      ),
    onSuccess: refetch,
  })
}

/** Turns the mirror off; what it recorded stays in the person's ledger. */
export function useTurnOffGroupMirror(groupId: string) {
  const refetch = useRefetchMirror(groupId)
  return useMoneyMutation({
    mutationFn: async (_: void, key: string) =>
      dataOrThrow(
        await api.DELETE('/v1/groups/{id}/mirror', {
          params: { path: { id: groupId }, header: { 'Idempotency-Key': key } },
        }),
      ),
    onSuccess: refetch,
  })
}
