import { vi } from 'vitest'
import type { Account, AccountUpdate, NewAccount } from '@/api/accounts'
import type { Category, CategoryKind, CategoryUpdate } from '@/api/categories'
import { api } from '@/api/client'
import type { Problem } from '@/api/problem'
import type { CurrentUser } from '@/api/session'
import type { NewTransaction, Transaction } from '@/api/transactions'
import { fakeTransactions, Refused } from './fakeTransactions'

/** An invented user for tests. */
export const testUser: CurrentUser = {
  id: '0199f0a2-7c3e-7d4b-9c1a-2b3c4d5e6f70',
  email: 'ana.teste@example.com',
  name: 'Ana Teste',
}

export function euros(amountMinor: number) {
  return { amountMinor, currency: 'EUR' }
}

let lastId = 0

/** A UUID-shaped id, different for every call. */
export function testId() {
  lastId += 1
  return `00000000-0000-4000-8000-${String(lastId).padStart(12, '0')}`
}

/** An invented account: an empty checking account unless `overrides` say otherwise. */
export function testAccount(
  overrides: Partial<Account> & Pick<Account, 'name'>,
): Account {
  return {
    id: testId(),
    type: 'CHECKING',
    currency: 'EUR',
    balance: euros(0),
    changeThisMonth: euros(0),
    archived: false,
    ...overrides,
  }
}

/** The default categories every user starts with, in the API's order, none renamed. */
export function defaultCategories(): Category[] {
  const expense = [
    'housing',
    'food',
    'transport',
    'bills',
    'shopping',
    'leisure',
    'health',
    'other',
  ]
  const income = ['salary', 'refunds', 'gifts', 'other-income']
  return [
    ...expense.map((key) => testCategory('EXPENSE', { key })),
    ...income.map((key) => testCategory('INCOME', { key })),
  ]
}

export function testCategory(
  kind: CategoryKind,
  overrides: Partial<Category>,
): Category {
  return { id: testId(), kind, archived: false, ...overrides }
}

/** An invented expense of the design canvas' October, unless `overrides` say otherwise. */
export function testTransaction(
  overrides: Partial<Transaction> & Pick<Transaction, 'description'>,
): Transaction {
  return {
    id: testId(),
    type: 'EXPENSE',
    amount: euros(1000),
    occurredOn: '2026-10-22',
    recordedAt: '2026-10-22T20:14:00Z',
    edited: false,
    reversed: false,
    ...overrides,
  }
}

type Answer = { data?: unknown; error?: unknown; response: Response }

function ok(data: unknown, status = 200): Answer {
  return { data, response: new Response(null, { status }) }
}

function problem(status: number, code: string): Answer {
  const error: Problem = { title: 'Error', status, code }
  return { error, response: new Response(null, { status }) }
}

type RequestInit = {
  params?: {
    path?: { id: string }
    header?: Record<string, string>
    query?: Record<string, string | number | undefined>
  }
  body?: unknown
}

/**
 * Stands in for the API: the health check, `/v1/me` with `user` (401 when it is null), and the
 * accounts, categories and transactions of that user, kept in memory so that what a test adds,
 * renames, archives, records or reverses shows up in the next list, as with the real API. The
 * money-moving POSTs need an Idempotency-Key and replay their first answer for the same request.
 *
 * Returns the spies, to check what was sent or to make one call fail with `mockImplementationOnce`,
 * and the fake's state.
 */
export function mockApi({
  user = testUser,
  accounts = [],
  categories = defaultCategories(),
  transactions = [],
}: {
  user?: CurrentUser | null
  accounts?: Account[]
  categories?: Category[]
  transactions?: Transaction[]
} = {}) {
  const state = {
    accounts: accounts.map((account) => ({ ...account })),
    categories: categories.map((category) => ({ ...category })),
  }
  const ledger = fakeTransactions(
    transactions,
    state.accounts,
    state.categories,
    testId,
  )
  const keys = new Map<string, { request: string; answer: Answer }>()

  /** Runs `handle` once per Idempotency-Key, as the API's idempotency flow does. */
  const idempotent = (
    path: string,
    init: RequestInit,
    handle: () => Answer,
  ) => {
    const key = init.params?.header?.['Idempotency-Key']
    if (!key) {
      return problem(400, 'bad_request')
    }
    const request = JSON.stringify([path, init.params?.path, init.body])
    const stored = keys.get(key)
    if (stored !== undefined) {
      return stored.request === request
        ? stored.answer
        : problem(422, 'idempotency_key_reused')
    }
    const answer = handle()
    if (answer.response.ok) {
      keys.set(key, { request, answer })
    }
    return answer
  }

  const get = vi.spyOn(api, 'GET').mockImplementation((async (
    path: string,
    init?: RequestInit,
  ) => {
    switch (path) {
      case '/v1/health':
        return ok({ status: 'UP' })
      case '/v1/me':
        return user === null ? problem(401, 'unauthenticated') : ok(user)
      // Copies, as JSON from the real API would be: never the objects a later request changes.
      case '/v1/accounts':
        return ok({ items: state.accounts.map((account) => ({ ...account })) })
      case '/v1/categories':
        return ok({
          items: state.categories.map((category) => ({ ...category })),
        })
      case '/v1/transactions':
        return ok(ledger.list(init?.params?.query ?? {}))
      case '/v1/transactions/{id}':
        return refusing(() => ok(ledger.detail(init?.params?.path?.id ?? '')))
    }
    throw new Error(`Unexpected GET ${path}`)
  }) as never)

  const post = vi.spyOn(api, 'POST').mockImplementation((async (
    path: string,
    init: RequestInit,
  ) => {
    if (path === '/v1/accounts') {
      if (!init.params?.header?.['Idempotency-Key']) {
        return problem(400, 'bad_request')
      }
      const { name, type, openingBalance } = init.body as NewAccount
      const account = testAccount({
        name: name.trim(),
        type,
        balance: openingBalance ?? euros(0),
      })
      state.accounts.push(account)
      return ok(account, 201)
    }
    if (path === '/v1/categories') {
      const { kind, name } = init.body as { kind: CategoryKind; name: string }
      if (nameTaken(state.categories, kind, name)) {
        return problem(409, 'category_name_taken')
      }
      const category = testCategory(kind, { name: name.trim() })
      state.categories.push(category)
      return ok(category, 201)
    }
    if (path === '/v1/transactions') {
      return idempotent(path, init, () =>
        refusing(() => ok(ledger.record(init.body as NewTransaction), 201)),
      )
    }
    if (path === '/v1/transactions/{id}/reverse') {
      const { replacement } = (init.body ?? {}) as {
        replacement?: NewTransaction
      }
      return idempotent(path, init, () =>
        refusing(() =>
          ok(ledger.reverse(init.params?.path?.id ?? '', replacement)),
        ),
      )
    }
    throw new Error(`Unexpected POST ${path}`)
  }) as never)

  const patch = vi.spyOn(api, 'PATCH').mockImplementation((async (
    path: string,
    init: RequestInit,
  ) => {
    const id = init.params?.path?.id
    if (path === '/v1/accounts/{id}') {
      const account = state.accounts.find((account) => account.id === id)
      if (account === undefined) {
        return problem(404, 'account_not_found')
      }
      Object.assign(account, changed(init.body as AccountUpdate))
      return ok({ ...account })
    }
    if (path === '/v1/categories/{id}') {
      const category = state.categories.find((category) => category.id === id)
      if (category === undefined) {
        return problem(404, 'category_not_found')
      }
      const change = changed(init.body as CategoryUpdate)
      const after = { ...category, ...change }
      const others = state.categories.filter((other) => other.id !== id)
      if (
        !after.archived &&
        nameTaken(others, after.kind, after.name ?? after.key ?? '')
      ) {
        return problem(409, 'category_name_taken')
      }
      Object.assign(category, change)
      return ok({ ...category })
    }
    throw new Error(`Unexpected PATCH ${path}`)
  }) as never)

  return { get, post, patch, state, ledger, transactions: ledger.versions }
}

/** The fake's refusals as problem answers. */
function refusing(handle: () => Answer): Answer {
  try {
    return handle()
  } catch (error) {
    if (error instanceof Refused) {
      return problem(error.status, error.code)
    }
    throw error
  }
}

/** What a PATCH body changes, as the API applies it. */
function changed({ name, archived }: { name?: string; archived?: boolean }) {
  return {
    ...(name !== undefined && { name: name.trim() }),
    ...(archived !== undefined && {
      archived,
      archivedAt: archived ? '2026-06-03T12:00:00Z' : undefined,
    }),
  }
}

/** As the API's unique index: the name of another active category of the kind, ignoring case. */
function nameTaken(categories: Category[], kind: CategoryKind, name: string) {
  return categories.some(
    (category) =>
      category.kind === kind &&
      !category.archived &&
      (category.name ?? category.key ?? '').toLowerCase() ===
        name.trim().toLowerCase(),
  )
}
