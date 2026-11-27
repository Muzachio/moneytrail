import { act, fireEvent, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { signInUrl } from '@/api/session'
import { i18n } from '@/i18n/i18n'
import {
  testGroup,
  testMember,
  testToken,
  type TestInvite,
} from '@/test/fakeGroups'
import { mockApi, testUser } from '@/test/mockApi'
import { renderApp } from '@/test/renderApp'

const TOKEN_KEY = 'moneytrail.invite'
const RETURN_KEY = 'moneytrail.invite.returnAfterSignIn'

const rita = testMember({ name: 'Rita' })
const token = testToken('ritasPortoWeekendLink')

/** Rita's group, which the signed-in person is not in (yet), and the link she sent them. */
function invitation({ member = false } = {}) {
  const group = testGroup({
    name: 'Porto weekend',
    members: [
      rita,
      testMember({ name: 'João' }),
      ...(member
        ? [testMember({ userId: testUser.id, name: testUser.name })]
        : []),
    ],
  })
  const invite: TestInvite = {
    id: crypto.randomUUID(),
    groupId: group.id,
    token,
    createdBy: { userId: rita.userId, name: 'Rita' },
    createdAt: '2026-11-25T10:00:00Z',
    expiresAt: new Date(Date.now() + 5 * 86_400_000).toISOString(),
  }
  return { groups: [group], invites: [invite], group }
}

/** Every request the app made, as JSON, to check that none carries the token where it should not. */
function requests(spies: { mock: { calls: unknown[][] } }[]) {
  return spies.flatMap((spy) => spy.mock.calls)
}

describe('JoinPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    sessionStorage.clear()
  })

  afterEach(() => {
    sessionStorage.clear()
  })

  it('takes the token from the fragment, out of the address bar, and asks a stranger to sign in', async () => {
    const { get, post } = mockApi({ user: null, ...invitation() })
    const { router } = renderApp(`/join#${token}`)

    expect(
      await screen.findByRole('heading', {
        name: 'You have been invited to a group',
      }),
    ).toBeInTheDocument()
    // Nothing about the group for someone who has not signed in (ADR-0013).
    expect(screen.queryByText('Porto weekend')).toBeNull()
    expect(router.state.location.pathname).toBe('/join')
    expect(router.state.location.hash).toBe('')
    expect(sessionStorage.getItem(TOKEN_KEY)).toBe(token)
    for (const call of requests([get, post])) {
      expect(JSON.stringify(call)).not.toContain(token)
    }

    const google = screen.getByRole('link', { name: 'Continue with Google' })
    expect(google).toHaveAttribute('href', signInUrl)
    fireEvent.click(google)
    expect(sessionStorage.getItem(RETURN_KEY)).toBe('1')
  })

  it('shows who invited the person to which group, and joins it with one tap', async () => {
    const { groups, ...api } = invitation()
    const { get, post } = mockApi({ ...api, groups })
    const { router } = renderApp(`/join#${token}`)

    expect(await screen.findByText('Rita invited you to')).toBeInTheDocument()
    expect(
      screen.getByRole('heading', { level: 1, name: 'Porto weekend' }),
    ).toBeInTheDocument()
    expect(screen.getByText('2 members · EUR')).toBeInTheDocument()
    expect(
      screen.getByText(
        "The members will see your name, your photo and the group's expenses. Never your own accounts.",
      ),
    ).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Join group' }))

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Porto weekend' }),
    ).toBeInTheDocument()
    expect(
      await screen.findByText('You, Rita and João · EUR'),
    ).toBeInTheDocument()
    expect(router.state.location.pathname).toBe(`/groups/${groups[0].id}`)
    expect(sessionStorage.getItem(TOKEN_KEY)).toBeNull()

    // The token went in the bodies of the lookup and the accept, and nowhere else.
    expect(post).toHaveBeenCalledWith('/v1/invites/lookup', { body: { token } })
    expect(post).toHaveBeenCalledWith('/v1/invites/accept', { body: { token } })
    const carrying = requests([get, post]).filter((call) =>
      JSON.stringify(call).includes(token),
    )
    expect(carrying.map(([path]) => path)).toEqual([
      '/v1/invites/lookup',
      '/v1/invites/accept',
    ])
  })

  it('goes on to the invite when Google sign-in comes back to the app', async () => {
    sessionStorage.setItem(TOKEN_KEY, token)
    sessionStorage.setItem(RETURN_KEY, '1')
    mockApi(invitation())
    const { router } = renderApp('/')

    expect(await screen.findByText('Rita invited you to')).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/join')
    expect(sessionStorage.getItem(RETURN_KEY)).toBeNull()
  })

  it('tells someone the app does not let in what to do, keeping the invite', async () => {
    sessionStorage.setItem(TOKEN_KEY, token)
    sessionStorage.setItem(RETURN_KEY, '1')
    mockApi({ user: null, ...invitation() })
    const { router } = renderApp('/?sign-in=not-invited')

    expect(
      await screen.findByRole('heading', {
        name: 'MoneyTrail is not open to this account yet',
      }),
    ).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Ask whoever sent you the link to add it',
    )
    expect(router.state.location.pathname).toBe('/join')
    expect(sessionStorage.getItem(TOKEN_KEY)).toBe(token)
    expect(
      screen.getByRole('link', { name: 'Use another Google account' }),
    ).toHaveAttribute('href', signInUrl)
  })

  it('says when an invite no longer works, and forgets it', async () => {
    mockApi(invitation())
    renderApp(`/join#${testToken('revokedOrExpiredLink')}`)

    expect(
      await screen.findByRole('heading', {
        name: 'This invite no longer works',
      }),
    ).toBeInTheDocument()
    expect(sessionStorage.getItem(TOKEN_KEY)).toBeNull()
    expect(
      screen.getByRole('link', { name: 'Go to MoneyTrail' }),
    ).toHaveAttribute('href', '/')
  })

  it('says the same without a token at all', async () => {
    mockApi(invitation())
    renderApp('/join')

    expect(
      await screen.findByRole('heading', {
        name: 'This invite no longer works',
      }),
    ).toBeInTheDocument()
  })

  it('opens the group for someone who is a member already', async () => {
    mockApi(invitation({ member: true }))
    const { router } = renderApp(`/join#${token}`)

    expect(
      await screen.findByText('You are already a member of'),
    ).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Open group' }))
    expect(
      await screen.findByText('You, Rita and João · EUR'),
    ).toBeInTheDocument()
    expect(router.state.location.pathname).toMatch(/^\/groups\//)
  })

  it('speaks European Portuguese', async () => {
    mockApi({ user: null, ...invitation() })
    renderApp(`/join#${token}`)
    await screen.findByRole('heading', {
      name: 'You have been invited to a group',
    })
    await act(() => i18n.changeLanguage('pt-PT'))

    expect(
      screen.getByRole('heading', { name: 'Tens um convite para um grupo' }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('link', { name: 'Continuar com o Google' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Privacidade' })).toHaveAttribute(
      'href',
      '/privacy',
    )
  })
})
