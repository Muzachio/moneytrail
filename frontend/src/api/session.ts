import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, apiBaseUrl, withCookies } from './client'
import { csrfHeaders } from './csrf'
import type { components } from './schema'

export type CurrentUser = components['schemas']['CurrentUser']

const currentUserKey = ['current-user'] as const

/** Starts Google sign-in: a full page navigation, because Google's page cannot load in a fetch. */
export const signInUrl = new URL('/oauth2/authorization/google', apiBaseUrl)
  .href

/** The signed-in user, or `null` without a session (the API answers 401). */
export function useCurrentUser() {
  return useQuery({
    queryKey: currentUserKey,
    queryFn: async (): Promise<CurrentUser | null> => {
      const { data, response } = await api.GET('/v1/me')
      if (response.status === 401) {
        return null
      }
      if (data === undefined) {
        throw new Error(
          `Loading the signed-in user failed (${response.status})`,
        )
      }
      return data
    },
    retry: false,
  })
}

/** Ends the session on the server (Spring Security's POST /logout, which needs the CSRF token). */
export function useSignOut() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async () => {
      const response = await fetch(new URL('/logout', apiBaseUrl), {
        method: 'POST',
        headers: csrfHeaders(),
        credentials: withCookies,
      })
      if (!response.ok) {
        throw new Error(`Signing out failed (${response.status})`)
      }
    },
    onSuccess: () => {
      queryClient.setQueryData(currentUserKey, null)
      // Every other cached answer belonged to the user who just left.
      queryClient.removeQueries({
        predicate: (query) => query.queryKey[0] !== currentUserKey[0],
      })
    },
  })
}
