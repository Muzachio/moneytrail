import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'
import { useMoneyMutation } from './moneyMutation'
import { dataOrThrow } from './problem'
import type { components } from './schema'

export type GroupSummary = components['schemas']['GroupSummary']
export type Group = components['schemas']['Group']
export type GroupMember = components['schemas']['GroupMember']
export type NewGroup = components['schemas']['NewGroup']
export type GroupUpdate = components['schemas']['GroupUpdate']
export type InviteLink = components['schemas']['InviteLink']
export type NewInviteLink = components['schemas']['NewInviteLink']
export type InvitePreview = components['schemas']['InvitePreview']
export type Profile = components['schemas']['Profile']

export const groupsKey = ['groups'] as const

function groupKey(id: string) {
  return [...groupsKey, id] as const
}

function invitesKey(groupId: string) {
  return [...groupKey(groupId), 'invites'] as const
}

/** The groups the signed-in person is in, archived ones included, with their balance in each. */
export function useGroups() {
  return useQuery({
    queryKey: groupsKey,
    queryFn: async () => dataOrThrow(await api.GET('/v1/groups')).items,
  })
}

/** A group and its members. Someone who is not a member gets `group_not_found`. */
export function useGroup(id: string) {
  return useQuery({
    queryKey: groupKey(id),
    queryFn: async () =>
      dataOrThrow(
        await api.GET('/v1/groups/{id}', { params: { path: { id } } }),
      ),
  })
}

/**
 * Creates a group with the person as its first member. It moves no money, but the API asks for an
 * Idempotency-Key all the same: groups may share a name, so a retry after a lost answer would
 * otherwise leave a second, empty group behind.
 */
export function useCreateGroup() {
  const queryClient = useQueryClient()
  return useMoneyMutation({
    mutationFn: async (group: NewGroup, idempotencyKey: string) =>
      dataOrThrow(
        await api.POST('/v1/groups', {
          params: { header: { 'Idempotency-Key': idempotencyKey } },
          body: group,
        }),
      ),
    onSuccess: (group) => {
      queryClient.setQueryData(groupKey(group.id), group)
      return queryClient.invalidateQueries({ queryKey: groupsKey, exact: true })
    },
  })
}

/** Renames, archives or restores a group. */
export function useUpdateGroup(id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (change: GroupUpdate) =>
      dataOrThrow(
        await api.PATCH('/v1/groups/{id}', {
          params: { path: { id } },
          body: change,
        }),
      ),
    onSuccess: (group) => {
      queryClient.setQueryData(groupKey(id), group)
      return queryClient.invalidateQueries({ queryKey: groupsKey, exact: true })
    },
  })
}

/** Leaves a group, which the API allows only with a balance of zero. */
export function useLeaveGroup(id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async () => {
      const { error, response } = await api.POST('/v1/groups/{id}/leave', {
        params: { path: { id } },
      })
      if (!response.ok) {
        dataOrThrow({ error, response })
      }
    },
    onSuccess: () => {
      // The group answers 404 to a former member: nothing cached about it is theirs any more.
      queryClient.removeQueries({ queryKey: groupKey(id) })
      return queryClient.invalidateQueries({ queryKey: groupsKey, exact: true })
    },
  })
}

/** The group's links that still work, newest first, without their tokens. */
export function useInvites(groupId: string) {
  return useQuery({
    queryKey: invitesKey(groupId),
    queryFn: async () =>
      dataOrThrow(
        await api.GET('/v1/groups/{id}/invites', {
          params: { path: { id: groupId } },
        }),
      ).items,
  })
}

/**
 * Makes a link. Its token is in this answer only, so it lives in the component that shows it and
 * is never cached: closing the dialog loses it, as the design says ("Shown once").
 */
export function useCreateInvite(groupId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async () =>
      dataOrThrow(
        await api.POST('/v1/groups/{id}/invites', {
          params: { path: { id: groupId } },
        }),
      ),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: invitesKey(groupId) }),
  })
}

export function useRevokeInvite(groupId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (inviteId: string) => {
      const { error, response } = await api.DELETE(
        '/v1/groups/{id}/invites/{inviteId}',
        { params: { path: { id: groupId, inviteId } } },
      )
      // Revoked already, or expired meanwhile: either way the link no longer works.
      if (!response.ok && response.status !== 404) {
        dataOrThrow({ error, response })
      }
    },
    onSettled: () =>
      queryClient.invalidateQueries({ queryKey: invitesKey(groupId) }),
  })
}

/**
 * Which group a token opens, for the join page. A `POST` with the token in the body, never in a URL
 * (ADR-0013); a token that opens nothing answers 404, `invite_not_found`, whatever the reason. Not
 * cached past the page: the key holds the token, and it is only kept in memory.
 */
export function useInvitePreview(token: string | null, enabled: boolean) {
  return useQuery({
    queryKey: ['invite-preview', token],
    queryFn: async () =>
      dataOrThrow(
        await api.POST('/v1/invites/lookup', { body: { token: token ?? '' } }),
      ),
    enabled: enabled && token !== null,
    retry: false,
    gcTime: 0,
  })
}

/** Joins the group a token opens; joining again, or as a member, changes nothing. */
export function useAcceptInvite() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (token: string) =>
      dataOrThrow(await api.POST('/v1/invites/accept', { body: { token } })),
    onSuccess: (group) => {
      queryClient.setQueryData(groupKey(group.id), group)
      return queryClient.invalidateQueries({ queryKey: groupsKey, exact: true })
    },
  })
}
