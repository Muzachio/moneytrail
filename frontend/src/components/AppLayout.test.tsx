import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockApi } from '@/test/mockApi'
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
})
