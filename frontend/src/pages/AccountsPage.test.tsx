import { act, fireEvent, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Account } from '@/api/accounts'
import { i18n } from '@/i18n/i18n'
import { euros, mockApi, testAccount } from '@/test/mockApi'
import { renderApp } from '@/test/renderApp'

// Intl separates Portuguese amounts and the euro sign with a no-break space (U+00A0).
const NBSP = ' '

/** The accounts of the design canvas: invented names and amounts. */
function designAccounts(): Account[] {
  return [
    testAccount({
      name: 'Everyday checking',
      type: 'CHECKING',
      balance: euros(218437),
      changeThisMonth: euros(41029),
    }),
    testAccount({
      name: 'Emergency fund',
      type: 'SAVINGS',
      balance: euros(650000),
      changeThisMonth: euros(20000),
    }),
    testAccount({
      name: 'Trip savings',
      type: 'SAVINGS',
      balance: euros(120000),
    }),
    testAccount({
      name: 'Wallet',
      type: 'CASH',
      balance: euros(4520),
      changeThisMonth: euros(-2270),
    }),
    testAccount({
      name: 'Credit card',
      type: 'CREDIT_CARD',
      balance: euros(-31240),
      changeThisMonth: euros(-31240),
    }),
    testAccount({
      name: 'Old savings',
      type: 'SAVINGS',
      archived: true,
      archivedAt: '2026-06-03T12:00:00Z',
    }),
  ]
}

function group(name: string) {
  return screen.getByRole('region', { name })
}

function row(name: string) {
  return screen.getByText(name).closest('li')!
}

/** Radix opens its menus on pointerdown, which jsdom does not send with a click; Enter works. */
async function chooseFromMenu(menuLabel: string, item: RegExp) {
  fireEvent.keyDown(screen.getByRole('button', { name: menuLabel }), {
    key: 'Enter',
  })
  fireEvent.click(await screen.findByRole('menuitem', { name: item }))
}

describe('AccountsPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('shows net worth, then each group with its accounts, balances and change this month', async () => {
    mockApi({ accounts: designAccounts() })

    renderApp('/accounts')

    const netWorth = await screen.findByRole('region', { name: 'Net worth' })
    expect(netWorth).toHaveTextContent('€9,617.17')
    expect(netWorth).toHaveTextContent('Up €275.19 this month')
    expect(within(netWorth).getByText('Assets').nextSibling).toHaveTextContent(
      '€9,929.57',
    )
    expect(
      within(netWorth).getByText('Liabilities').nextSibling,
    ).toHaveTextContent('−€312.40')

    const cash = group('Cash and bank')
    expect(cash).toHaveTextContent('€9,929.57')
    expect(within(cash).getAllByRole('listitem')).toHaveLength(4)
    expect(row('Everyday checking')).toHaveTextContent('This month: +€410.29')
    expect(row('Everyday checking')).toHaveTextContent('Balance: €2,184.37')
    expect(row('Trip savings')).toHaveTextContent('No change')
    expect(row('Wallet')).toHaveTextContent('−€22.70')

    const cards = group('Credit cards')
    expect(cards).toHaveTextContent('−€312.40')
    expect(row('Credit card')).toHaveTextContent('Credit card · what you owe')

    // Archived accounts stay out of the groups until their section is opened.
    expect(screen.queryByText('Old savings')).not.toBeVisible()
    fireEvent.click(
      screen.getByRole('button', { name: 'Archived accounts (1)' }),
    )
    expect(row('Old savings')).toBeVisible()
    expect(row('Old savings')).toHaveTextContent(
      'Savings · archived on 3 June 2026',
    )
  })

  it('counts archived accounts in net worth, since their money is still there', async () => {
    mockApi({
      accounts: [
        testAccount({ name: 'Everyday checking', balance: euros(10000) }),
        testAccount({
          name: 'Old savings',
          type: 'SAVINGS',
          balance: euros(2500),
          archived: true,
        }),
      ],
    })

    renderApp('/accounts')

    expect(
      await screen.findByRole('region', { name: 'Net worth' }),
    ).toHaveTextContent('€125.00')
    expect(group('Cash and bank')).toHaveTextContent('€100.00')
  })

  it('speaks Portuguese', async () => {
    await act(() => i18n.changeLanguage('pt-PT'))
    mockApi({ accounts: designAccounts() })

    renderApp('/accounts')

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Contas' }),
    ).toBeInTheDocument()
    const netWorth = await screen.findByRole('region', {
      name: 'Património líquido',
    })
    expect(netWorth).toHaveTextContent(`9617,17${NBSP}€`)
    expect(netWorth).toHaveTextContent(`Subiu 275,19${NBSP}€ este mês`)
    expect(row('Everyday checking')).toHaveTextContent('Conta à ordem')
    expect(row('Everyday checking')).toHaveTextContent(`+410,29${NBSP}€`)
    expect(row('Credit card')).toHaveTextContent(
      'Cartão de crédito · o que deves',
    )
    fireEvent.click(
      screen.getByRole('button', { name: 'Contas arquivadas (1)' }),
    )
    expect(row('Old savings')).toHaveTextContent(
      'Poupança · arquivada a 3 de junho de 2026',
    )
  })

  it('says so while the accounts are loading', async () => {
    const { get } = mockApi()
    const answer = get.getMockImplementation()!
    get.mockImplementation(((path: string) =>
      path === '/v1/accounts'
        ? new Promise(() => {})
        : answer(path as never)) as never)

    renderApp('/accounts')

    expect(await screen.findByText('Loading your accounts…')).toHaveAttribute(
      'role',
      'status',
    )
  })

  it('offers to add the first account when there is none', async () => {
    mockApi({ accounts: [] })

    renderApp('/accounts')

    expect(await screen.findByText('No accounts yet')).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Net worth' })).toBeNull()
    const addButtons = screen.getAllByRole('button', { name: 'Add account' })
    fireEvent.click(addButtons[addButtons.length - 1])
    expect(
      await screen.findByRole('dialog', { name: 'Add account' }),
    ).toBeInTheDocument()
  })

  it('says when the accounts could not be loaded, and tries again', async () => {
    const { get } = mockApi({ accounts: designAccounts() })
    const answer = get.getMockImplementation()!
    get.mockImplementation(((path: string) =>
      path === '/v1/accounts'
        ? Promise.resolve({
            error: { title: 'Error', status: 503, code: 'unavailable' },
            response: new Response(null, { status: 503 }),
          })
        : answer(path as never)) as never)

    renderApp('/accounts')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Could not load your accounts.')
    get.mockImplementation(answer)
    fireEvent.click(within(alert).getByRole('button', { name: 'Try again' }))
    expect(
      await screen.findByRole('region', { name: 'Net worth' }),
    ).toBeInTheDocument()
  })

  it('renames an account from its menu', async () => {
    const { patch } = mockApi({ accounts: designAccounts() })
    renderApp('/accounts')
    await screen.findByText('Wallet')

    await chooseFromMenu('More actions for Wallet', /Rename/)
    const dialog = await screen.findByRole('dialog', {
      name: 'Rename account',
    })
    const name = within(dialog).getByLabelText('Name')
    expect(name).toHaveValue('Wallet')
    fireEvent.change(name, { target: { value: '  Pocket money ' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('Pocket money')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).toBeNull()
    // Keyboard users carry on from the row they were on.
    expect(
      screen.getByRole('button', { name: 'More actions for Pocket money' }),
    ).toHaveFocus()
    expect(patch).toHaveBeenCalledWith('/v1/accounts/{id}', {
      params: { path: { id: expect.any(String) } },
      body: { name: 'Pocket money' },
    })
  })

  it('does not send a rename that changes nothing, and refuses an empty name', async () => {
    const { patch } = mockApi({ accounts: designAccounts() })
    renderApp('/accounts')
    await screen.findByText('Wallet')

    await chooseFromMenu('More actions for Wallet', /Rename/)
    const dialog = await screen.findByRole('dialog', {
      name: 'Rename account',
    })
    const name = within(dialog).getByLabelText('Name')
    fireEvent.change(name, { target: { value: '   ' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'Enter a name.',
    )
    expect(name).toHaveAttribute('aria-invalid', 'true')
    expect(name).toHaveAccessibleDescription('Enter a name.')

    fireEvent.change(name, { target: { value: 'Wallet' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(patch).not.toHaveBeenCalled()
  })

  it('archives an account and restores it', async () => {
    const { patch } = mockApi({ accounts: designAccounts() })
    renderApp('/accounts')
    await screen.findByText('Wallet')

    await chooseFromMenu('More actions for Wallet', /Archive/)

    const archived = await screen.findByRole('button', {
      name: 'Archived accounts (2)',
    })
    expect(within(group('Cash and bank')).queryByText('Wallet')).toBeNull()
    expect(patch).toHaveBeenCalledWith('/v1/accounts/{id}', {
      params: { path: { id: expect.any(String) } },
      body: { archived: true },
    })
    // Its money still counts.
    expect(screen.getByRole('region', { name: 'Net worth' })).toHaveTextContent(
      '€9,617.17',
    )

    fireEvent.click(archived)
    fireEvent.click(screen.getByRole('button', { name: 'Restore Wallet' }))

    expect(
      await screen.findByRole('button', { name: 'Archived accounts (1)' }),
    ).toBeInTheDocument()
    expect(within(group('Cash and bank')).getByText('Wallet')).toBeVisible()
    expect(patch).toHaveBeenLastCalledWith('/v1/accounts/{id}', {
      params: { path: { id: expect.any(String) } },
      body: { archived: false },
    })
  })

  it('says when archiving failed', async () => {
    const { patch } = mockApi({ accounts: designAccounts() })
    patch.mockResolvedValueOnce({
      error: { title: 'Not Found', status: 404, code: 'account_not_found' },
      response: new Response(null, { status: 404 }),
    } as never)
    renderApp('/accounts')
    await screen.findByText('Wallet')

    await chooseFromMenu('More actions for Wallet', /Archive/)

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Could not archive Wallet. This no longer exists. Reload the page.',
    )
    expect(within(group('Cash and bank')).getByText('Wallet')).toBeVisible()
  })
})
