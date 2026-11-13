import {
  type DefaultError,
  useMutation,
  type UseMutationOptions,
} from '@tanstack/react-query'
import { useRef } from 'react'

export type MoneyMutationOptions<TData, TError, TInput, TOnMutateResult> = Omit<
  UseMutationOptions<TData, TError, TInput, TOnMutateResult>,
  'mutationFn'
> & {
  /**
   * Sends `input` with `idempotencyKey` as the request's Idempotency-Key header, e.g.
   * `api.POST('/v1/transactions', { params: { header: { 'Idempotency-Key': idempotencyKey } }, body })`.
   */
  mutationFn: (input: TInput, idempotencyKey: string) => Promise<TData>
  /**
   * One key for every request, chosen by the caller instead of one per input (below): the
   * transaction dialog keeps one for as long as it is open. Whatever the person changes after a
   * failed attempt, the API then records the movement at most once: an attempt whose answer was
   * lost is replayed, or refused with `idempotency_key_reused` if the input changed since.
   */
  idempotencyKey?: string
}

/**
 * A mutation that moves money, sent with an Idempotency-Key so the API records it once however
 * many times the request reaches it (architecture.md, "Idempotency flow").
 *
 * A key belongs to one action of the person until it succeeds: TanStack Query's automatic retries,
 * pressing Save again after a network error, or a second tap send the same input, and so the same
 * key. A changed input is a new action with a new key, since the API refuses a key reused with a
 * different body. After a success, the same input is a new action too: two identical coffees are
 * two expenses.
 */
export function useMoneyMutation<
  TData = unknown,
  TError = DefaultError,
  TInput = void,
  TOnMutateResult = unknown,
>({
  mutationFn,
  idempotencyKey,
  ...options
}: MoneyMutationOptions<TData, TError, TInput, TOnMutateResult>) {
  // Lives as long as the component: closing the form ends the action, unfinished or not.
  const unfinished = useRef<{ input: string; key: string } | null>(null)

  return useMutation<TData, TError, TInput, TOnMutateResult>({
    ...options,
    mutationFn: async (input) => {
      if (idempotencyKey !== undefined) {
        return mutationFn(input, idempotencyKey)
      }
      // Compared as JSON, the form the API hashes to tell a retry from a different request.
      const json = JSON.stringify(input) ?? ''
      if (unfinished.current?.input !== json) {
        unfinished.current = { input: json, key: crypto.randomUUID() }
      }
      const action = unfinished.current
      const data = await mutationFn(input, action.key)
      if (unfinished.current === action) {
        unfinished.current = null
      }
      return data
    },
  })
}
