import { fireEvent, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockApi, testUser } from '@/test/mockApi'
import { renderApp } from '@/test/renderApp'

describe('AccountMenu', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    document.cookie = 'XSRF-TOKEN=test-csrf-token'
  })

  afterEach(() => {
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT'
  })

  it('shows the signed-in user with initials when there is no photo', async () => {
    mockApi()

    renderApp()

    expect(await screen.findByText(testUser.name)).toBeInTheDocument()
    expect(screen.getByText('AT')).toBeInTheDocument()
  })

  it('shows the profile photo when Google provides one', async () => {
    mockApi({
      user: { ...testUser, avatarUrl: 'https://example.com/avatar.png' },
    })

    renderApp()

    await screen.findByText(testUser.name)
    expect(document.querySelector('img')).toHaveAttribute(
      'src',
      'https://example.com/avatar.png',
    )
  })

  it('signs out with the CSRF token and returns to the sign-in page', async () => {
    mockApi()
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(new Response(null, { status: 204 }))

    renderApp()
    fireEvent.click(await screen.findByRole('button', { name: 'Sign out' }))

    expect(
      await screen.findByRole('heading', { name: 'Sign in to MoneyTrail' }),
    ).toBeInTheDocument()
    const [url, init] = fetchMock.mock.calls[0]
    expect(String(url)).toBe(`${window.location.origin}/logout`)
    expect(init).toMatchObject({
      method: 'POST',
      headers: { 'X-XSRF-TOKEN': 'test-csrf-token' },
      credentials: 'include',
    })
  })
})
