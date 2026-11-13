import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useMoneyMutation } from './moneyMutation'

type Expense = { description: string; amountMinor: number }

const coffee: Expense = { description: 'Coffee', amountMinor: 120 }
const networkError = new TypeError('Failed to fetch')

function renderMoneyMutation(
  send: (input: Expense, idempotencyKey: string) => Promise<string>,
  { retry = 0 } = {},
) {
  const queryClient = new QueryClient()
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  return renderHook(
    () => useMoneyMutation({ mutationFn: send, retry, retryDelay: 0 }),
    { wrapper },
  )
}

function sentKeys(send: ReturnType<typeof vi.fn>) {
  return send.mock.calls.map(([, idempotencyKey]) => idempotencyKey as string)
}

describe('useMoneyMutation', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('sends each action with a new random key, even with the same input', async () => {
    const randomUUID = vi.spyOn(crypto, 'randomUUID')
    const send = vi.fn().mockResolvedValue('saved')
    const { result } = renderMoneyMutation(send)

    await act(() => result.current.mutateAsync(coffee))
    // A second coffee: the same input, but another expense.
    await act(() => result.current.mutateAsync({ ...coffee }))

    const [first, second] = sentKeys(send)
    expect(first).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-/)
    expect(second).not.toBe(first)
    expect(randomUUID).toHaveBeenCalledTimes(2)
  })

  it('keeps the key when TanStack Query retries the action', async () => {
    const send = vi
      .fn()
      .mockRejectedValueOnce(networkError)
      .mockResolvedValue('saved')
    const { result } = renderMoneyMutation(send, { retry: 1 })

    await act(() => result.current.mutateAsync(coffee))

    const [first, retried] = sentKeys(send)
    expect(retried).toBe(first)
  })

  it('keeps the key when the person tries again after an error', async () => {
    const send = vi
      .fn()
      .mockRejectedValueOnce(networkError)
      .mockResolvedValue('saved')
    const { result } = renderMoneyMutation(send)

    await act(async () => {
      await expect(result.current.mutateAsync(coffee)).rejects.toBe(
        networkError,
      )
    })
    await act(() => result.current.mutateAsync({ ...coffee }))

    const [first, retried] = sentKeys(send)
    expect(retried).toBe(first)
  })

  it('keeps the key for a second tap while the first is on its way', async () => {
    const answers: ((value: string) => void)[] = []
    const send = vi.fn(
      () => new Promise<string>((resolve) => answers.push(resolve)),
    )
    const { result } = renderMoneyMutation(send)

    let taps: Promise<string>[] = []
    act(() => {
      taps = [
        result.current.mutateAsync(coffee),
        result.current.mutateAsync(coffee),
      ]
    })
    await vi.waitFor(() => expect(send).toHaveBeenCalledTimes(2))
    await act(async () => {
      answers.forEach((answer) => answer('saved'))
      await Promise.all(taps)
    })

    const [firstKey, secondKey] = sentKeys(send)
    expect(secondKey).toBe(firstKey)
  })

  it('starts a new action when the input changes after an error', async () => {
    const send = vi
      .fn()
      .mockRejectedValueOnce(networkError)
      .mockResolvedValue('saved')
    const { result } = renderMoneyMutation(send)

    await act(async () => {
      await expect(result.current.mutateAsync(coffee)).rejects.toBe(
        networkError,
      )
    })
    await act(() => result.current.mutateAsync({ ...coffee, amountMinor: 150 }))

    const [first, corrected] = sentKeys(send)
    expect(corrected).not.toBe(first)
  })
})

describe('useMoneyMutation with a key of its own', () => {
  it('sends every attempt with the caller’s key, changed input or not', async () => {
    const queryClient = new QueryClient()
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    const send = vi
      .fn()
      .mockRejectedValueOnce(networkError)
      .mockResolvedValue('saved')
    const { result } = renderHook(
      () =>
        useMoneyMutation({
          mutationFn: send,
          idempotencyKey: 'one-key-per-dialog',
        }),
      { wrapper },
    )

    await act(async () => {
      await expect(result.current.mutateAsync(coffee)).rejects.toBe(
        networkError,
      )
    })
    await act(() => result.current.mutateAsync({ ...coffee, amountMinor: 150 }))

    expect(sentKeys(send)).toEqual(['one-key-per-dialog', 'one-key-per-dialog'])
  })
})
