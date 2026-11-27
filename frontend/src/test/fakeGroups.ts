import type {
  Group,
  GroupMember,
  GroupSummary,
  GroupUpdate,
  InviteLink,
  NewGroup,
  Profile,
} from '@/api/groups'
import type { NewGroupExpense } from '@/api/groupExpenses'
import type { CurrentUser } from '@/api/session'
import type { Money } from '@/lib/money'
import {
  fakeGroupExpenses,
  type TestExpense,
  type TestSettlement,
} from './fakeGroupExpenses'

/** An invented member of a group. */
export function testMember(
  overrides: Partial<GroupMember> & Pick<GroupMember, 'name'>,
): GroupMember {
  return {
    userId: crypto.randomUUID(),
    joinedAt: '2026-11-14T10:00:00Z',
    ...overrides,
  }
}

/** An invented group as the fake keeps it: the group, and the signed-in person's balance in it. */
export type TestGroup = Group & { myBalance: Money }

export function testGroup(
  overrides: Partial<TestGroup> & Pick<TestGroup, 'name' | 'members'>,
): TestGroup {
  return {
    id: crypto.randomUUID(),
    currency: 'EUR',
    archived: false,
    createdAt: '2026-11-14T10:00:00Z',
    myBalance: { amountMinor: 0, currency: 'EUR' },
    ...overrides,
  }
}

/** An invented invite link of a group, with the token the API showed once. */
export type TestInvite = InviteLink & { groupId: string; token: string }

/** A token in the API's format: 43 base64url characters, different for every call. */
export function testToken(seed = Math.random().toString(36).slice(2)) {
  return seed
    .replace(/[^A-Za-z0-9_-]/g, '')
    .padEnd(43, 'x')
    .slice(0, 43)
}

/** What a handler answers, which `mockApi` turns into openapi-fetch's shape. */
export type FakeAnswer = { status: number; data?: unknown; code?: string }

type Init = {
  params?: {
    path?: { id?: string; inviteId?: string; expenseId?: string }
    query?: { cursor?: string; limit?: number }
  }
  body?: unknown
}

/**
 * The groups and invites part of the fake API: groups the signed-in person is a member of, their
 * invite links, and the lookup and accept of a token, kept in memory as the real API keeps them,
 * and the groups' expenses and payments (`fakeGroupExpenses`). Group ids of other people's groups
 * answer `group_not_found`, as for a stranger.
 */
export function fakeGroups(
  groups: TestGroup[],
  invites: TestInvite[],
  user: CurrentUser | null,
  expenses: TestExpense[] = [],
  settlements: TestSettlement[] = [],
) {
  const state = {
    groups: groups.map((group) => ({ ...group })),
    invites: invites.map((invite) => ({ ...invite })),
    /** Groups the person is not in (yet): what an invite to someone else's group leads to. */
    others: [] as TestGroup[],
  }
  const expensesFake = fakeGroupExpenses(expenses, settlements)

  const me: GroupMember | undefined = user
    ? { userId: user.id, name: user.name, joinedAt: '2026-11-27T09:00:00Z' }
    : undefined
  const isMember = (group: TestGroup) =>
    me !== undefined &&
    group.members.some((member) => member.userId === me.userId)
  const find = (id: string | undefined) =>
    state.groups.find((group) => group.id === id && isMember(group))
  const detail = (group: TestGroup): Group => {
    const { myBalance, ...rest } = group
    void myBalance
    return { ...rest, members: rest.members.map((member) => ({ ...member })) }
  }
  const summary = (group: TestGroup): GroupSummary => ({
    id: group.id,
    name: group.name,
    currency: group.currency,
    memberCount: group.members.length,
    myBalance: { ...group.myBalance },
    archived: group.archived,
    ...(group.archivedAt && { archivedAt: group.archivedAt }),
  })
  const notFound: FakeAnswer = { status: 404, code: 'group_not_found' }
  const inviteNotFound: FakeAnswer = { status: 404, code: 'invite_not_found' }
  const activeInvite = (token: unknown) =>
    state.invites.find(
      (invite) =>
        invite.token === token &&
        Date.parse(invite.expiresAt) > Date.now() &&
        !allGroups().find((group) => group.id === invite.groupId)?.archived,
    )
  const allGroups = () => [...state.groups, ...state.others]

  function get(path: string, init?: Init): FakeAnswer | undefined {
    const id = init?.params?.path?.id
    switch (path) {
      case '/v1/groups':
        return {
          status: 200,
          data: { items: state.groups.filter(isMember).map(summary) },
        }
      case '/v1/groups/{id}': {
        const group = find(id)
        return group ? { status: 200, data: detail(group) } : notFound
      }
      case '/v1/groups/{id}/expenses': {
        const group = find(id)
        return group
          ? expensesFake.list(group, init?.params?.query ?? {})
          : notFound
      }
      case '/v1/groups/{id}/expenses/{expenseId}': {
        const group = find(id)
        return group
          ? expensesFake.detail(group, init?.params?.path?.expenseId)
          : notFound
      }
      case '/v1/groups/{id}/invites': {
        const group = find(id)
        if (!group) {
          return notFound
        }
        const items = state.invites
          .filter(
            (invite) =>
              invite.groupId === group.id &&
              Date.parse(invite.expiresAt) > Date.now(),
          )
          .map(({ id, createdBy, createdAt, expiresAt }) => ({
            id,
            createdBy,
            createdAt,
            expiresAt,
          }))
          .reverse()
        return { status: 200, data: { items } }
      }
    }
    return undefined
  }

  function post(path: string, init: Init): FakeAnswer | undefined {
    const id = init.params?.path?.id
    switch (path) {
      case '/v1/groups': {
        const { name, currency = 'EUR' } = init.body as NewGroup
        const group = testGroup({
          name: name.trim(),
          currency,
          members: me ? [{ ...me }] : [],
        })
        state.groups.push(group)
        return { status: 201, data: detail(group) }
      }
      case '/v1/groups/{id}/expenses': {
        const group = find(id)
        return group
          ? expensesFake.create(group, init.body as NewGroupExpense, me!.userId)
          : notFound
      }
      case '/v1/groups/{id}/expenses/{expenseId}/reverse': {
        const group = find(id)
        const { replacement } = (init.body ?? {}) as {
          replacement?: NewGroupExpense
        }
        return group
          ? expensesFake.reverse(
              group,
              init.params?.path?.expenseId,
              replacement,
              me!.userId,
            )
          : notFound
      }
      case '/v1/groups/{id}/leave': {
        const group = find(id)
        if (!group) {
          return notFound
        }
        if (group.myBalance.amountMinor !== 0) {
          return { status: 409, code: 'member_not_settled' }
        }
        group.members = group.members.filter(
          (member) => member.userId !== me?.userId,
        )
        return { status: 204 }
      }
      case '/v1/groups/{id}/invites': {
        const group = find(id)
        if (!group) {
          return notFound
        }
        if (group.archived) {
          return { status: 409, code: 'group_archived' }
        }
        const createdAt = new Date().toISOString()
        const invite: TestInvite = {
          id: crypto.randomUUID(),
          groupId: group.id,
          token: testToken(),
          createdBy: profile(me!),
          createdAt,
          expiresAt: new Date(Date.now() + 7 * 86_400_000).toISOString(),
        }
        state.invites.push(invite)
        const { token, expiresAt } = invite
        return {
          status: 201,
          data: { id: invite.id, token, createdAt, expiresAt },
        }
      }
      case '/v1/invites/lookup': {
        const invite = activeInvite((init.body as { token?: string }).token)
        const group = allGroups().find(({ id }) => id === invite?.groupId)
        if (!invite || !group) {
          return inviteNotFound
        }
        return {
          status: 200,
          data: {
            groupName: group.name,
            currency: group.currency,
            memberCount: group.members.length,
            invitedBy: invite.createdBy,
            alreadyMember: isMember(group),
          },
        }
      }
      case '/v1/invites/accept': {
        const invite = activeInvite((init.body as { token?: string }).token)
        const group = allGroups().find(({ id }) => id === invite?.groupId)
        if (!invite || !group || !me) {
          return inviteNotFound
        }
        if (!isMember(group)) {
          group.members = [...group.members, { ...me }]
        }
        if (!state.groups.includes(group)) {
          state.groups.push(group)
          state.others = state.others.filter((other) => other !== group)
        }
        return { status: 200, data: detail(group) }
      }
    }
    return undefined
  }

  function patch(path: string, init: Init): FakeAnswer | undefined {
    if (path !== '/v1/groups/{id}') {
      return undefined
    }
    const group = find(init.params?.path?.id)
    if (!group) {
      return notFound
    }
    const { name, archived } = init.body as GroupUpdate
    if (archived === true && group.myBalance.amountMinor !== 0) {
      return { status: 409, code: 'group_not_settled' }
    }
    if (name !== undefined) {
      group.name = name.trim()
    }
    if (archived !== undefined) {
      group.archived = archived
      group.archivedAt = archived ? '2026-11-27T12:00:00Z' : undefined
    }
    return { status: 200, data: detail(group) }
  }

  function remove(path: string, init: Init): FakeAnswer | undefined {
    if (path !== '/v1/groups/{id}/invites/{inviteId}') {
      return undefined
    }
    const group = find(init.params?.path?.id)
    if (!group) {
      return notFound
    }
    const index = state.invites.findIndex(
      (invite) =>
        invite.id === init.params?.path?.inviteId &&
        invite.groupId === group.id,
    )
    if (index < 0) {
      return { status: 404, code: 'invite_not_found' }
    }
    state.invites.splice(index, 1)
    return { status: 204 }
  }

  return { state, expenses: expensesFake.state, get, post, patch, remove }
}

function profile({ userId, name, avatarUrl }: GroupMember): Profile {
  return { userId, name, ...(avatarUrl && { avatarUrl }) }
}
