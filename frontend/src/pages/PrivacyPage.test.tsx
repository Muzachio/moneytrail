import { fireEvent, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockApi } from '@/test/mockApi'
import { renderApp } from '@/test/renderApp'

describe('PrivacyPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    mockApi({ user: null })
  })

  it('opens without a session and says what is kept, where and who sees it', async () => {
    renderApp('/privacy')

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Privacy' }),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('heading', { name: 'Sign in to MoneyTrail' }),
    ).not.toBeInTheDocument()
    for (const title of [
      'What is stored',
      'Where',
      'Who can see it',
      'No ads, no trackers',
      'Asking for a copy or for deletion',
    ]) {
      expect(
        screen.getByRole('heading', { level: 2, name: title }),
      ).toBeInTheDocument()
    }
    expect(
      screen.getByText(/name, email address and profile photo/),
    ).toBeInTheDocument()
    expect(
      screen.getByText(/Neon .* Fly\.io, both in Frankfurt/),
    ).toBeInTheDocument()
    expect(screen.getByText(/Grafana Cloud, in the EU/)).toBeInTheDocument()
    expect(
      screen.getByText(/maintains MoneyTrail has technical access/),
    ).toBeInTheDocument()
    expect(screen.getByText(/done by hand/)).toBeInTheDocument()
    // What groups show to other people (#91, ADR-0013).
    expect(
      screen.getByText(
        "In a group, its members see your name and photo, and the group's expenses and payments. They never see your email or your own accounts and transactions.",
      ),
    ).toBeInTheDocument()
  })

  it('reads in Portuguese', async () => {
    renderApp('/privacy')

    fireEvent.click(await screen.findByRole('button', { name: 'Português' }))

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Privacidade' }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('heading', { level: 2, name: 'O que fica guardado' }),
    ).toBeInTheDocument()
    expect(screen.getByText(/feito à mão/)).toBeInTheDocument()
    expect(
      screen.getByText(
        'Num grupo, os membros veem o teu nome e a tua foto, e as despesas e os pagamentos do grupo. Nunca veem o teu email nem as tuas contas e movimentos pessoais.',
      ),
    ).toBeInTheDocument()
  })

  it('leads back to the sign-in page', async () => {
    renderApp('/privacy')

    fireEvent.click(
      await screen.findByRole('link', { name: 'Back to MoneyTrail' }),
    )

    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: 'Sign in to MoneyTrail',
      }),
    ).toBeInTheDocument()
  })
})
