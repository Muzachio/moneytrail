import { fireEvent, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockApi, testUser } from '@/test/mockApi'
import { renderApp } from '@/test/renderApp'

async function openAccountMenu() {
  fireEvent.click(await screen.findByRole('button', { name: 'Your account' }))
  return screen.findByRole('dialog')
}

describe('AccountMenu', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    document.cookie = 'XSRF-TOKEN=test-csrf-token'
  })

  afterEach(() => {
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT'
  })

  it('shows initials when there is no photo, and the user in the menu', async () => {
    mockApi()

    renderApp()

    const trigger = await screen.findByRole('button', { name: 'Your account' })
    expect(trigger).toHaveTextContent('AT')
    const menu = await openAccountMenu()
    expect(menu).toHaveTextContent(testUser.name)
    expect(menu).toHaveTextContent(testUser.email)
  })

  it('shows the profile photo when Google provides one', async () => {
    mockApi({
      user: { ...testUser, avatarUrl: 'https://example.com/avatar.png' },
    })

    renderApp()

    const trigger = await screen.findByRole('button', { name: 'Your account' })
    expect(trigger.querySelector('img')).toHaveAttribute(
      'src',
      'https://example.com/avatar.png',
    )
  })

  it('switches the whole app to Portuguese', async () => {
    mockApi()

    renderApp()
    const menu = await openAccountMenu()
    fireEvent.click(within(menu).getByRole('button', { name: 'Português' }))

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Resumo' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: 'Principal' })).toBeVisible()
    expect(
      within(menu).getByRole('group', { name: 'Idioma' }),
    ).toBeInTheDocument()
    expect(
      within(menu).getByRole('button', { name: 'Português' }),
    ).toHaveAttribute('aria-pressed', 'true')
    expect(document.documentElement).toHaveAttribute('lang', 'pt-PT')
  })

  it('signs out with the CSRF token and returns to the sign-in page', async () => {
    mockApi()
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(new Response(null, { status: 204 }))

    renderApp()
    const menu = await openAccountMenu()
    fireEvent.click(within(menu).getByRole('button', { name: 'Sign out' }))

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
