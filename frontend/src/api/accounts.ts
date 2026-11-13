import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'
import { useMoneyMutation } from './moneyMutation'
import { dataOrThrow } from './problem'
import type { components } from './schema'

export type Account = components['schemas']['Account']
export type AccountType = components['schemas']['AccountType']
export type NewAccount = components['schemas']['NewAccount']
export type AccountUpdate = components['schemas']['AccountUpdate']

export const accountsKey = ['accounts'] as const

/** Every account of the signed-in user, archived ones included, in the order they were created. */
export function useAccounts() {
  return useQuery({
    queryKey: accountsKey,
    queryFn: async () => dataOrThrow(await api.GET('/v1/accounts')).items,
  })
}

/**
 * Adds an account. Its opening balance moves money from the user's opening balances account, so
 * the request carries an Idempotency-Key: a second tap or a retry never opens two accounts.
 */
export function useCreateAccount() {
  const queryClient = useQueryClient()
  return useMoneyMutation({
    mutationFn: async (account: NewAccount, idempotencyKey: string) =>
      dataOrThrow(
        await api.POST('/v1/accounts', {
          params: { header: { 'Idempotency-Key': idempotencyKey } },
          body: account,
        }),
      ),
    // Waited for, so the new account is on the list when the dialog closes.
    onSuccess: () => queryClient.invalidateQueries({ queryKey: accountsKey }),
  })
}

/** Renames, archives or restores an account. It moves no money, so it needs no Idempotency-Key. */
export function useUpdateAccount() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ id, ...change }: AccountUpdate & { id: string }) =>
      dataOrThrow(
        await api.PATCH('/v1/accounts/{id}', {
          params: { path: { id } },
          body: change,
        }),
      ),
    onSuccess: (updated) =>
      queryClient.setQueryData<Account[]>(accountsKey, (accounts) =>
        accounts?.map((account) =>
          account.id === updated.id ? updated : account,
        ),
      ),
  })
}
