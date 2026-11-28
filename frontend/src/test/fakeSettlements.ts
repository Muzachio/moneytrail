import type { GroupTrailStep } from '@/api/groupExpenses'
import type {
  ActivityItem,
  MemberBalance,
  NewSettlement,
  SettlementDetail,
  SuggestedPayment,
} from '@/api/groupSettlements'
import type { GroupMember, Profile } from '@/api/groups'
import type { FakeAnswer } from './fakeGroups'
import type { TestExpense, TestSettlement } from './fakeGroupExpenses'

/** An invented item of a group's activity feed. */
export type TestActivity = ActivityItem & { groupId: string }

let clock = Date.parse('2026-11-16T12:00:00Z')

/** An invented item of `groupId`'s feed; each one a minute after the last unless it says when. */
export function testActivity(
  groupId: string,
  overrides: Pick<ActivityItem, 'kind' | 'actorId'> & Partial<ActivityItem>,
): TestActivity {
  clock += 60_000
  return {
    id: crypto.randomUUID(),
    groupId,
    occurredAt: new Date(clock).toISOString(),
    ...overrides,
  }
}

type Group = {
  id: string
  currency: string
  archived: boolean
  members: GroupMember[]
}

type Versions = {
  versions: TestExpense[]
  settlements: TestSettlement[]
}

/**
 * The balances, settle-up and payments part of the fake API, on the same expenses and payments as
 * `fakeGroupExpenses`: each member's balance worked out from what is in effect, the greedy
 * suggestions of ADR-0012, payments recorded and reversed with their trail, and the activity feed
 * a test gives it.
 */
export function fakeSettlements(
  shared: Versions,
  activity: readonly TestActivity[],
  formerMembers: readonly Profile[],
) {
  const state = {
    /** The trail of each payment, by the id of its first version. */
    trails: new Map<string, GroupTrailStep[]>(),
    roots: new Map<string, string>(),
    activity: activity.map((item) => ({ ...item })),
  }
  for (const settlement of shared.settlements) {
    state.roots.set(settlement.id, settlement.id)
    state.trails.set(settlement.id, [recordedStep(settlement)])
  }

  function recordedStep(version: TestSettlement): GroupTrailStep {
    return {
      entryId: version.id,
      action: 'RECORDED',
      amount: { ...version.amount },
      recordedBy: version.recordedBy,
      recordedAt: version.recordedAt,
    }
  }

  const isMember = (group: Group, userId: string) =>
    group.members.some((member) => member.userId === userId)
  const find = (group: Group, settlementId: string | undefined) =>
    shared.settlements.find(
      (settlement) =>
        settlement.id === settlementId && settlement.groupId === group.id,
    )
  const copy = ({ groupId, ...settlement }: TestSettlement) => {
    void groupId
    return structuredClone(settlement)
  }
  const detail = (version: TestSettlement): SettlementDetail => {
    const root = state.roots.get(version.id)!
    const current = shared.settlements.find(
      (other) => state.roots.get(other.id) === root && !other.reversed,
    )
    return {
      settlement: copy(version),
      trail: structuredClone(state.trails.get(root)!),
      ...(current && { currentId: current.id }),
    }
  }

  /** What the group owes each current member, from the expenses and payments in effect. */
  function balancesOf(group: Group): MemberBalance[] {
    const owed = new Map(group.members.map(({ userId }) => [userId, 0]))
    const add = (userId: string, amount: number) => {
      if (owed.has(userId)) {
        owed.set(userId, owed.get(userId)! + amount)
      }
    }
    for (const expense of shared.versions) {
      if (expense.groupId === group.id && !expense.reversed) {
        add(expense.paidBy, expense.amount.amountMinor)
        for (const member of expense.split.members) {
          add(member.userId, -member.share.amountMinor)
        }
      }
    }
    for (const payment of shared.settlements) {
      if (payment.groupId === group.id && !payment.reversed) {
        add(payment.from, payment.amount.amountMinor)
        add(payment.to, -payment.amount.amountMinor)
      }
    }
    return group.members.map(({ userId }) => ({
      userId,
      balance: { amountMinor: owed.get(userId)!, currency: group.currency },
    }))
  }

  /** The greedy suggestions: who owes the most pays who is owed the most, ties by id as text. */
  function suggestionsOf(group: Group): SuggestedPayment[] {
    const left = balancesOf(group).map(({ userId, balance }) => ({
      userId,
      amount: balance.amountMinor,
    }))
    const payments: SuggestedPayment[] = []
    const byId = (a: { userId: string }, b: { userId: string }) =>
      a.userId < b.userId ? -1 : a.userId > b.userId ? 1 : 0
    for (;;) {
      const debtor = left
        .filter(({ amount }) => amount < 0)
        .sort((a, b) => a.amount - b.amount || byId(a, b))[0]
      const creditor = left
        .filter(({ amount }) => amount > 0)
        .sort((a, b) => b.amount - a.amount || byId(a, b))[0]
      if (debtor === undefined || creditor === undefined) {
        return payments
      }
      const amount = Math.min(-debtor.amount, creditor.amount)
      payments.push({
        from: debtor.userId,
        to: creditor.userId,
        amount: { amountMinor: amount, currency: group.currency },
      })
      debtor.amount += amount
      creditor.amount -= amount
    }
  }

  return {
    state,

    balances(group: Group): FakeAnswer {
      return { status: 200, data: { items: balancesOf(group) } }
    },

    settleUp(group: Group): FakeAnswer {
      return { status: 200, data: { items: suggestionsOf(group) } }
    },

    create(group: Group, body: NewSettlement, me: string): FakeAnswer {
      if (group.archived) {
        return { status: 409, code: 'group_archived' }
      }
      if (!isMember(group, body.from) || !isMember(group, body.to)) {
        return { status: 422, code: 'member_not_found' }
      }
      if (body.from === body.to) {
        return { status: 422, code: 'group_settlement_to_self' }
      }
      if (body.amount.currency !== group.currency) {
        return { status: 422, code: 'currency_mismatch' }
      }
      clock += 60_000
      const version: TestSettlement = {
        id: crypto.randomUUID(),
        groupId: group.id,
        amount: { ...body.amount },
        occurredOn: body.occurredOn,
        from: body.from,
        to: body.to,
        recordedBy: me,
        recordedAt: new Date(clock).toISOString(),
        edited: false,
        reversed: false,
      }
      shared.settlements.push(version)
      state.roots.set(version.id, version.id)
      state.trails.set(version.id, [recordedStep(version)])
      return { status: 201, data: copy(version) }
    },

    detail(group: Group, settlementId: string | undefined): FakeAnswer {
      const version = find(group, settlementId)
      return version === undefined
        ? { status: 404, code: 'group_settlement_not_found' }
        : { status: 200, data: detail(version) }
    },

    reverse(group: Group, settlementId: string | undefined, me: string) {
      const version = find(group, settlementId)
      if (version === undefined) {
        return { status: 404, code: 'group_settlement_not_found' }
      }
      if (group.archived) {
        return { status: 409, code: 'group_archived' }
      }
      if (version.reversed) {
        return { status: 409, code: 'group_settlement_already_reversed' }
      }
      version.reversed = true
      clock += 60_000
      state.trails.get(state.roots.get(version.id)!)!.push({
        entryId: crypto.randomUUID(),
        action: 'REVERSED',
        amount: { ...version.amount },
        recordedBy: me,
        recordedAt: new Date(clock).toISOString(),
        reversesEntryId: version.id,
      })
      return { status: 200, data: detail(version) }
    },

    activity(group: Group, query: { cursor?: string; limit?: number }) {
      const items = state.activity
        .filter((item) => item.groupId === group.id)
        .sort((a, b) => b.occurredAt.localeCompare(a.occurredAt))
      // The real cursor is opaque; an offset is enough for a feed nobody adds to while paging.
      const start = Number(query.cursor ?? 0)
      const limit = Number(query.limit ?? 50)
      const page = items
        .slice(start, start + limit)
        .map(({ groupId, ...item }) => {
          void groupId
          return structuredClone(item)
        })
      const named = new Set(
        page.flatMap((item) =>
          [item.actorId, item.paidBy, item.from, item.to].filter(
            (id): id is string => id !== undefined,
          ),
        ),
      )
      const profiles: Profile[] = [...group.members, ...formerMembers].map(
        ({ userId, name, avatarUrl }) => ({
          userId,
          name,
          ...(avatarUrl && { avatarUrl }),
        }),
      )
      return {
        status: 200,
        data: {
          items: page,
          people: profiles.filter(({ userId }) => named.has(userId)),
          ...(start + limit < items.length && {
            nextCursor: String(start + limit),
          }),
        },
      }
    },
  }
}
