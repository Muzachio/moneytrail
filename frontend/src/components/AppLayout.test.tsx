import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import { renderApp } from '@/test/renderApp'

describe('AppLayout', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.spyOn(api, 'GET').mockResolvedValue({ data: { status: 'UP' } } as never)
  })

  it('shows the main navigation and the summary page', () => {
    renderApp('/')

    const nav = screen.getByRole('navigation', { name: 'Main' })
    expect(nav).toHaveTextContent('Summary')
    expect(nav).toHaveTextContent('Accounts')
    expect(nav).toHaveTextContent('Transactions')
    expect(
      screen.getByRole('heading', { level: 1, name: 'Summary' }),
    ).toBeInTheDocument()
  })

  it('marks the link of the current page as active', () => {
    renderApp('/accounts')

    expect(
      screen.getByRole('heading', { level: 1, name: 'Accounts' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Accounts' })).toHaveAttribute(
      'aria-current',
      'page',
    )
  })

  it('shows a not found page for unknown paths', () => {
    renderApp('/does-not-exist')

    expect(
      screen.getByRole('heading', { level: 1, name: 'Page not found' }),
    ).toBeInTheDocument()
  })
})
