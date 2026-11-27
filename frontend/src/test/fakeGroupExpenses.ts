import type {
  GroupEntry,
  GroupExpense,
  GroupExpenseCategory,
  GroupExpenseDetail,
  GroupTrailStep,
  NewGroupExpense,
  Settlement,
} from '@/api/groupExpenses'
import { divide, type SplitMethod } from '@/lib/splits'
import type { FakeAnswer } from './fakeGroups'

/** An invented expense of a group, as the fake keeps it: one version, and the group it is in. */
export type TestExpense = GroupExpense & { groupId: string }

/** An invented payment between two members of a group. */
export type TestSettlement = Settlement & { groupId: string }

let clock = Date.parse('2026-11-14T12:00:00Z')

/** One minute after the last one: versions recorded one after another keep their order. */
function nextInstant() {
  clock += 60_000
  return new Date(clock).toISOString()
}

/** The split as the API keeps it: each member's input and share, in id order. */
function splitOf(
  amountMinor: number,
  currency: string,
  method: SplitMethod,
  members: readonly { userId: string; value?: number }[],
) {
  const result = divide(
    BigInt(amountMinor),
    method,
    members.map(({ userId, value }) => ({
      member: userId,
      value: BigInt(value ?? 1),
    })),
  )
  if (!result.ok) {
    return result
  }
  return {
    ok: true as const,
    split: {
      method,
      members: result.shares.map(({ member, value, share }) => ({
        userId: member,
        value: Number(value),
        share: { amountMinor: Number(share), currency },
      })),
    },
  }
}

/**
 * An invented expense of `groupId`, split as the API would: `members` are the people in it, with
 * their shares, percentages or amounts unless the split is equal.
 */
export function testExpense(
  groupId: string,
  {
    amount,
    members,
    method = 'EQUALLY',
    currency = 'EUR',
    category = 'food',
    ...overrides
  }: {
    amount: number
    paidBy: string
    members: { userId: string; value?: number }[]
    method?: SplitMethod
    currency?: string
    category?: GroupExpenseCategory
  } & Partial<
    Pick<
      GroupExpense,
      | 'id'
      | 'description'
      | 'occurredOn'
      | 'recordedBy'
      | 'recordedAt'
      | 'edited'
    >
  >,
): TestExpense {
  const split = splitOf(amount, currency, method, members)
  if (!split.ok) {
    throw new Error(
      `An invented expense with a split the API refuses: ${split.error}`,
    )
  }
  return {
    id: crypto.randomUUID(),
    groupId,
    amount: { amountMinor: amount, currency },
    description: 'Dinner',
    occurredOn: '2026-11-14',
    category,
    split: split.split,
    recordedBy: overrides.paidBy,
    recordedAt: nextInstant(),
    edited: false,
    reversed: false,
    ...overrides,
  }
}

/** An invented payment from one member to another. */
export function testSettlement(
  groupId: string,
  overrides: Pick<Settlement, 'from' | 'to'> & { amount: number } & Partial<
      Pick<Settlement, 'id' | 'occurredOn' | 'recordedAt'>
    >,
): TestSettlement {
  const { amount, ...rest } = overrides
  return {
    id: crypto.randomUUID(),
    groupId,
    amount: { amountMinor: amount, currency: 'EUR' },
    occurredOn: '2026-11-16',
    recordedBy: overrides.from,
    recordedAt: nextInstant(),
    edited: false,
    reversed: false,
    ...rest,
  }
}

type Group = {
  id: string
  currency: string
  archived: boolean
  members: { userId: string }[]
}

/**
 * The expenses part of the fake API: each version of each expense with the trail it belongs to,
 * recorded, reversed and edited as the real API does it, and the group's payments for its list.
 */
export function fakeGroupExpenses(
  expenses: readonly TestExpense[],
  settlements: readonly TestSettlement[],
) {
  const state = {
    versions: expenses.map((expense) => ({ ...expense })),
    settlements: settlements.map((settlement) => ({ ...settlement })),
    /** The trail of each expense, by the id of its first version. */
    trails: new Map<string, GroupTrailStep[]>(),
    /** Which expense each version belongs to: the id of its first version. */
    roots: new Map<string, string>(),
  }
  for (const version of state.versions) {
    state.roots.set(version.id, version.id)
    state.trails.set(version.id, [step(version, 'RECORDED')])
  }

  function step(
    version: GroupExpense,
    action: GroupTrailStep['action'],
    reversed?: GroupExpense,
    by?: string,
  ): GroupTrailStep {
    return reversed === undefined
      ? {
          entryId: version.id,
          action,
          amount: { ...version.amount },
          category: version.category,
          recordedBy: version.recordedBy,
          recordedAt: version.recordedAt,
        }
      : {
          entryId: crypto.randomUUID(),
          action,
          amount: { ...reversed.amount },
          category: reversed.category,
          recordedBy: by ?? reversed.recordedBy,
          recordedAt: nextInstant(),
          reversesEntryId: reversed.id,
        }
  }

  const copy = ({ groupId, ...expense }: TestExpense): GroupExpense => {
    void groupId
    return structuredClone(expense)
  }
  const isMember = (group: Group, userId: string) =>
    group.members.some((member) => member.userId === userId)
  const find = (group: Group, expenseId: string | undefined) =>
    state.versions.find(
      (version) => version.id === expenseId && version.groupId === group.id,
    )
  const detail = (version: TestExpense): GroupExpenseDetail => {
    const root = state.roots.get(version.id)!
    const trail = state.trails.get(root)!
    const current = state.versions.find(
      (other) => state.roots.get(other.id) === root && !other.reversed,
    )
    return {
      expense: copy(version),
      trail: structuredClone(trail),
      ...(current && { currentId: current.id }),
    }
  }

  /** Checks a new expense or a replacement as the API does, and records it. */
  function record(
    group: Group,
    body: NewGroupExpense,
    me: string,
    edited: boolean,
  ): TestExpense | FakeAnswer {
    if (
      !isMember(group, body.paidBy) ||
      body.split.members.some(({ userId }) => !isMember(group, userId))
    ) {
      return { status: 422, code: 'member_not_found' }
    }
    if (body.amount.currency !== group.currency) {
      return { status: 422, code: 'currency_mismatch' }
    }
    const split = splitOf(
      body.amount.amountMinor,
      body.amount.currency,
      body.split.method,
      body.split.members,
    )
    if (!split.ok) {
      return { status: 422, code: split.error }
    }
    const version: TestExpense = {
      id: crypto.randomUUID(),
      groupId: group.id,
      amount: { ...body.amount },
      occurredOn: body.occurredOn,
      description: body.description.trim(),
      category: body.category,
      paidBy: body.paidBy,
      split: split.split,
      recordedBy: me,
      recordedAt: nextInstant(),
      edited,
      reversed: false,
    }
    state.versions.push(version)
    return version
  }

  return {
    state,

    list(group: Group, query: { cursor?: string; limit?: number }): FakeAnswer {
      const entries: (GroupEntry & { sort: [string, string] })[] = [
        ...state.versions
          .filter(
            (version) => version.groupId === group.id && !version.reversed,
          )
          .map((version) => ({
            type: 'EXPENSE' as const,
            expense: copy(version),
            sort: [version.occurredOn, version.recordedAt] as [string, string],
          })),
        ...state.settlements
          .filter(
            (settlement) =>
              settlement.groupId === group.id && !settlement.reversed,
          )
          .map(({ groupId, ...settlement }) => {
            void groupId
            return {
              type: 'SETTLEMENT' as const,
              settlement: structuredClone(settlement),
              sort: [settlement.occurredOn, settlement.recordedAt] as [
                string,
                string,
              ],
            }
          }),
      ].sort((a, b) =>
        a.sort[0] === b.sort[0]
          ? b.sort[1].localeCompare(a.sort[1])
          : b.sort[0].localeCompare(a.sort[0]),
      )
      // The real cursor is opaque; an offset is enough for a list nobody changes while paging.
      const start = Number(query.cursor ?? 0)
      const limit = Number(query.limit ?? 50)
      const page = entries
        .slice(start, start + limit)
        .map(({ sort, ...entry }) => {
          void sort
          return entry
        })
      return {
        status: 200,
        data: {
          items: page,
          ...(start + limit < entries.length && {
            nextCursor: String(start + limit),
          }),
        },
      }
    },

    create(group: Group, body: NewGroupExpense, me: string): FakeAnswer {
      if (group.archived) {
        return { status: 409, code: 'group_archived' }
      }
      const version = record(group, body, me, false)
      if (!('id' in version)) {
        return version
      }
      state.roots.set(version.id, version.id)
      state.trails.set(version.id, [step(version, 'RECORDED')])
      return { status: 201, data: copy(version) }
    },

    detail(group: Group, expenseId: string | undefined): FakeAnswer {
      const version = find(group, expenseId)
      return version === undefined
        ? { status: 404, code: 'group_expense_not_found' }
        : { status: 200, data: detail(version) }
    },

    reverse(
      group: Group,
      expenseId: string | undefined,
      replacement: NewGroupExpense | undefined,
      me: string,
    ): FakeAnswer {
      const version = find(group, expenseId)
      if (version === undefined) {
        return { status: 404, code: 'group_expense_not_found' }
      }
      if (group.archived) {
        return { status: 409, code: 'group_archived' }
      }
      if (version.reversed) {
        return { status: 409, code: 'group_expense_already_reversed' }
      }
      const root = state.roots.get(version.id)!
      let correction: TestExpense | undefined
      if (replacement !== undefined) {
        const recorded = record(group, replacement, me, true)
        if (!('id' in recorded)) {
          return recorded
        }
        correction = recorded
      }
      version.reversed = true
      const trail = state.trails.get(root)!
      trail.push(step(version, 'REVERSED', version, me))
      if (correction !== undefined) {
        state.roots.set(correction.id, root)
        trail.push(step(correction, 'CORRECTED'))
      }
      return { status: 200, data: detail(correction ?? version) }
    },
  }
}
