import { vi } from 'vitest'
import { api } from '@/api/client'
import type { CurrentUser } from '@/api/session'

/** An invented user for tests. */
export const testUser: CurrentUser = {
  id: '0199f0a2-7c3e-7d4b-9c1a-2b3c4d5e6f70',
  email: 'ana.teste@example.com',
  name: 'Ana Teste',
}

/**
 * Answers the API calls the shell makes: the health check, and /v1/me with `user`, or 401 when
 * `user` is null (no session).
 */
export function mockApi({
  user = testUser,
}: { user?: CurrentUser | null } = {}) {
  return vi.spyOn(api, 'GET').mockImplementation((async (path: string) => {
    if (path === '/v1/health') {
      return {
        data: { status: 'UP' },
        response: new Response(null, { status: 200 }),
      }
    }
    if (path === '/v1/me') {
      return user === null
        ? {
            error: { code: 'unauthenticated' },
            response: new Response(null, { status: 401 }),
          }
        : { data: user, response: new Response(null, { status: 200 }) }
    }
    throw new Error(`Unexpected GET ${path}`)
  }) as never)
}
