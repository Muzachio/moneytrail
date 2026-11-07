import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockApi } from '@/test/mockApi'
import { renderApp } from '@/test/renderApp'

describe('SignInPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    mockApi({ user: null })
  })

  it('replaces the app when there is no session', async () => {
    renderApp('/accounts')

    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: 'Sign in to MoneyTrail',
      }),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('navigation', { name: 'Main' }),
    ).not.toBeInTheDocument()
  })

  it('starts Google sign-in on the API with a full page navigation', async () => {
    renderApp('/')

    const link = await screen.findByRole('link', {
      name: 'Continue with Google',
    })
    expect(link).toHaveAttribute(
      'href',
      `${window.location.origin}/oauth2/authorization/google`,
    )
  })

  it('explains a failed sign-in', async () => {
    renderApp('/?sign-in=failed')

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Signing in did not work',
    )
  })
})
