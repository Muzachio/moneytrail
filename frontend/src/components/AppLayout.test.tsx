import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockApi } from '@/test/mockApi'
import { onPhone } from '@/test/phone'
import { renderApp } from '@/test/renderApp'

describe('AppLayout', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    mockApi()
  })

  it('shows the main navigation and the summary page', async () => {
    renderApp('/')

    const nav = await screen.findByRole('navigation', { name: 'Main' })
    expect(nav).toHaveTextContent('Summary')
    expect(nav).toHaveTextContent('Accounts')
    expect(nav).toHaveTextContent('Transactions')
    expect(
      screen.getByRole('heading', { level: 1, name: 'Summary' }),
    ).toBeInTheDocument()
  })

  it('marks the link of the current page as active', async () => {
    renderApp('/accounts')

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Accounts' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Accounts' })).toHaveAttribute(
      'aria-current',
      'page',
    )
  })

  it('shows a not found page for unknown paths', async () => {
    renderApp('/does-not-exist')

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Page not found' }),
    ).toBeInTheDocument()
  })

  describe('on a phone', () => {
    beforeEach(() => {
      onPhone()
    })

    afterEach(() => {
      vi.unstubAllGlobals()
    })

    it('moves the navigation to tabs at the bottom, with the new transaction in the centre', async () => {
      renderApp('/transactions')

      const tabs = await screen.findByRole('navigation', { name: 'Main' })
      expect(
        [...tabs.children].map(
          (tab) => tab.getAttribute('aria-label') ?? tab.textContent,
        ),
      ).toEqual([
        'Summary',
        'Transactions',
        'New transaction',
        'Groups',
        'Accounts',
      ])
      expect(
        within(tabs).getByRole('link', { name: 'Transactions' }),
      ).toHaveAttribute('aria-current', 'page')
      // Only one of each: the header keeps the name, the API's status and the account menu.
      expect(screen.getAllByRole('navigation')).toHaveLength(1)
      expect(
        screen.getAllByRole('button', { name: 'New transaction' }),
      ).toEqual([within(tabs).getByRole('button', { name: 'New transaction' })])
      expect(
        within(tabs).queryByRole('button', { name: 'Your account' }),
      ).toBeNull()
    })

    it('opens the new-transaction dialog from the centre button, and gives the focus back', async () => {
      renderApp('/')

      const tabs = await screen.findByRole('navigation', { name: 'Main' })
      const add = within(tabs).getByRole('button', { name: 'New transaction' })
      fireEvent.click(add)

      const dialog = await screen.findByRole('dialog', {
        name: 'New transaction',
      })
      fireEvent.keyDown(dialog, { key: 'Escape' })
      await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
      expect(add).toHaveFocus()
    })

    it('opens the account panel from the avatar in the header, where the You tab went', async () => {
      renderApp('/')

      await screen.findByRole('navigation', { name: 'Main' })
      fireEvent.click(screen.getByRole('button', { name: 'Your account' }))

      expect(
        await screen.findByRole('button', { name: 'Sign out' }),
      ).toBeInTheDocument()
      expect(screen.getByText('ana.teste@example.com')).toBeInTheDocument()
    })
  })
})
