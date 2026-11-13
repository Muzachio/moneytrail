import { act, fireEvent, screen, within, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '@/i18n/i18n'
import { mockApi, testAccount } from '@/test/mockApi'
import { OCTOBER_25, october } from '@/test/october'
import { renderApp } from '@/test/renderApp'

const networkError = new TypeError('Failed to fetch')
// Intl separates Portuguese amounts and the euro sign with a no-break space (U+00A0).
const NBSP = ' '

type Sent = [
  path: string,
  init: { params: { header: Record<string, string> }; body: unknown },
]

/** The calls of `POST /v1/transactions`, typed: the spy cannot see openapi-fetch's overloads. */
function sentTransactions(post: ReturnType<typeof mockApi>['post']) {
  return (post.mock.calls as unknown as Sent[]).filter(
    ([path]) => path === '/v1/transactions',
  )
}

function sentKeys(post: ReturnType<typeof mockApi>['post']) {
  return sentTransactions(post).map(
    ([, init]) => init.params.header['Idempotency-Key'],
  )
}

function fill(dialog: HTMLElement, label: string | RegExp, value: string) {
  fireEvent.change(within(dialog).getByLabelText(label), {
    target: { value },
  })
}

describe('TransactionDialog', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.useFakeTimers({ now: OCTOBER_25, toFake: ['Date'] })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  /** Opens the dialog from the header of the transactions page. */
  async function openNewTransaction(data = october()) {
    const api = mockApi(data)
    renderApp('/transactions')
    await screen.findByText('9 transactions in October')
    fireEvent.click(screen.getByRole('button', { name: 'New transaction' }))
    const dialog = await screen.findByRole('dialog', {
      name: 'New transaction',
    })
    // The accounts and categories it offers.
    await within(dialog).findByRole('radio', { name: 'Food' })
    return { ...data, ...api, dialog }
  }

  it('records an expense, today by default, with an Idempotency-Key', async () => {
    const { dialog, post, checking, category, transactions } =
      await openNewTransaction()

    expect(within(dialog).getByRole('radio', { name: 'Expense' })).toBeChecked()
    expect(within(dialog).getByLabelText('Date')).toHaveValue('2026-10-25')
    expect(within(dialog).getByLabelText('Paid from')).toHaveDisplayValue(
      'Everyday checking · €2,184.37',
    )
    fill(dialog, 'Amount', '12,34')
    fill(dialog, 'Description', ' Bakery ')
    fireEvent.click(within(dialog).getByRole('radio', { name: 'Food' }))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add expense' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(sentTransactions(post)).toEqual([
      [
        '/v1/transactions',
        {
          params: { header: { 'Idempotency-Key': expect.any(String) } },
          body: {
            type: 'EXPENSE',
            amount: { amountMinor: 1234, currency: 'EUR' },
            occurredOn: '2026-10-25',
            description: 'Bakery',
            categoryId: category('food').id,
            accountId: checking.id,
          },
        },
      ],
    ])
    expect(sentKeys(post)[0]).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4/)
    expect(transactions).toHaveLength(10)
    // The list is fetched again, so the new expense is on it when the dialog closes.
    const today = screen.getByRole('region', {
      name: 'Today, Sunday, 25 October',
    })
    expect(today).toHaveTextContent('Bakery')
    expect(today).toHaveTextContent('Net for the day: −€30.74')
  })

  it('records an income into the account it was received in', async () => {
    const { dialog, post, wallet, category } = await openNewTransaction()

    fireEvent.click(within(dialog).getByRole('radio', { name: 'Income' }))
    expect(
      within(dialog).queryByRole('radio', { name: 'Food' }),
    ).not.toBeInTheDocument()
    fill(dialog, 'Amount', '50')
    fill(dialog, 'Description', 'Birthday present')
    fireEvent.click(within(dialog).getByRole('radio', { name: 'Gifts' }))
    fill(dialog, 'Received in', wallet.id)
    fill(dialog, 'Date', '2026-10-18')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add income' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(sentTransactions(post)[0][1].body).toEqual({
      type: 'INCOME',
      amount: { amountMinor: 5000, currency: 'EUR' },
      occurredOn: '2026-10-18',
      description: 'Birthday present',
      categoryId: category('gifts').id,
      accountId: wallet.id,
    })
  })

  it('records a transfer between two accounts, which is neither spending nor income', async () => {
    const { dialog, post, checking, wallet } = await openNewTransaction()

    fireEvent.click(within(dialog).getByRole('radio', { name: 'Transfer' }))
    expect(dialog).toHaveTextContent(
      'Transfers move money between your own accounts, so they never count as spending or income.',
    )
    expect(
      within(dialog).queryByRole('group', { name: 'Category' }),
    ).not.toBeInTheDocument()
    expect(within(dialog).getByLabelText('From')).toHaveValue(checking.id)
    fill(dialog, 'Amount', '60')
    fill(dialog, 'Description', 'Cash for the week')
    fill(dialog, 'To', wallet.id)
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Add transfer' }),
    )

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(sentTransactions(post)[0][1].body).toEqual({
      type: 'TRANSFER',
      amount: { amountMinor: 6000, currency: 'EUR' },
      occurredOn: '2026-10-25',
      description: 'Cash for the week',
      fromAccountId: checking.id,
      toAccountId: wallet.id,
    })
    // Today's net is still the brunch's alone.
    expect(
      screen.getByRole('region', { name: 'Today, Sunday, 25 October' }),
    ).toHaveTextContent('Net for the day: −€18.40')
    expect(
      screen.getByText('10 transactions in October').parentElement,
    ).toHaveTextContent('Spending −€149.42')
  })

  it('checks everything before sending anything', async () => {
    const { dialog, post, checking } = await openNewTransaction()

    fireEvent.click(within(dialog).getByRole('button', { name: 'Add expense' }))
    expect(await within(dialog).findByText('Enter an amount.')).toBeVisible()
    expect(
      within(dialog).getByText('Describe it in a few words.'),
    ).toBeVisible()
    expect(within(dialog).getByText('Pick a category.')).toBeVisible()
    expect(within(dialog).getByLabelText('Amount')).toHaveFocus()

    fill(dialog, 'Amount', '0,00')
    fill(dialog, 'Description', 'x'.repeat(201))
    fill(dialog, 'Date', '')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add expense' }))
    expect(
      await within(dialog).findByText('Enter an amount above zero.'),
    ).toBeVisible()
    expect(
      within(dialog).getByText('Use at most 200 characters.'),
    ).toBeVisible()
    expect(within(dialog).getByText('Pick a date.')).toBeVisible()

    fill(dialog, 'Amount', '12.345')
    fireEvent.click(within(dialog).getByRole('radio', { name: 'Transfer' }))
    fill(dialog, 'To', checking.id)
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Add transfer' }),
    )
    expect(
      await within(dialog).findByText('Use at most 2 decimal places.'),
    ).toBeVisible()
    expect(
      within(dialog).getByText('Pick two different accounts.'),
    ).toBeVisible()
    expect(sentTransactions(post)).toEqual([])
  })

  it('records the transaction once, even when Save is tapped twice', async () => {
    const { dialog, post, transactions } = await openNewTransaction()
    fill(dialog, 'Amount', '3,20')
    fill(dialog, 'Description', 'Coffee')
    fireEvent.click(within(dialog).getByRole('radio', { name: 'Food' }))

    const save = within(dialog).getByRole('button', { name: 'Add expense' })
    fireEvent.click(save)
    fireEvent.click(save)

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    const keys = sentKeys(post)
    expect(keys.length).toBeGreaterThanOrEqual(1)
    expect(new Set(keys).size).toBe(1)
    expect(
      transactions.filter(({ description }) => description === 'Coffee'),
    ).toHaveLength(1)
  })

  it('keeps its key when the person tries again, even after changing something', async () => {
    const { dialog, post, transactions } = await openNewTransaction()
    post.mockRejectedValueOnce(networkError)
    fill(dialog, 'Amount', '3,20')
    fill(dialog, 'Description', 'Coffe')
    fireEvent.click(within(dialog).getByRole('radio', { name: 'Food' }))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add expense' }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'Could not reach MoneyTrail. Check your connection and try again.',
    )
    fill(dialog, 'Description', 'Coffee')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add expense' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    const [first, retried] = sentKeys(post)
    expect(retried).toBe(first)
    expect(
      transactions.filter(({ description }) => description.startsWith('Coff')),
    ).toHaveLength(1)
  })

  it('says so when an attempt whose answer was lost had already saved it', async () => {
    const { dialog, post, transactions } = await openNewTransaction()
    const answer = post.getMockImplementation()!
    // Saved by the API, but the answer never arrives.
    post.mockImplementationOnce((async (...args: Parameters<typeof answer>) => {
      await answer(...args)
      throw networkError
    }) as never)
    fill(dialog, 'Amount', '3,20')
    fill(dialog, 'Description', 'Coffee')
    fireEvent.click(within(dialog).getByRole('radio', { name: 'Food' }))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add expense' }))
    await within(dialog).findByText(
      'Could not reach MoneyTrail. Check your connection and try again.',
    )

    // A new key here would record a second coffee.
    fill(dialog, 'Amount', '3,40')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add expense' }))

    expect(
      await within(dialog).findByText(
        'An earlier attempt was already saved. Close this window and check the list.',
      ),
    ).toBeVisible()
    expect(
      transactions.filter(({ description }) => description === 'Coffee'),
    ).toHaveLength(1)
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(
      screen.getByRole('region', { name: 'Today, Sunday, 25 October' }),
    ).toHaveTextContent('Coffee')
  })

  it('creates a category from the "New category" chip and picks it', async () => {
    const { dialog, post, state } = await openNewTransaction()

    fireEvent.click(
      within(dialog).getByRole('button', { name: 'New category' }),
    )
    const nameDialog = await screen.findByRole('dialog', {
      name: 'New expense category',
    })
    fill(nameDialog, 'Name', 'Pets')
    fireEvent.click(
      within(nameDialog).getByRole('button', { name: 'Add category' }),
    )

    const pets = await within(dialog).findByRole('radio', { name: 'Pets' })
    await waitFor(() => expect(pets).toBeChecked())
    expect(post).toHaveBeenCalledWith('/v1/categories', {
      body: { kind: 'EXPENSE', name: 'Pets' },
    })
    // Creating the category sent nothing else: the transaction waits for Save.
    expect(sentTransactions(post)).toEqual([])
    expect(screen.getByRole('dialog', { name: 'New transaction' })).toBe(dialog)
    expect(state.categories.at(-1)).toMatchObject({
      kind: 'EXPENSE',
      name: 'Pets',
    })
  })

  it('does not offer archived accounts and categories', async () => {
    const data = october()
    data.wallet.archived = true
    data.category('leisure').archived = true
    const { dialog } = await openNewTransaction(data)

    expect(
      within(dialog).queryByRole('radio', { name: 'Leisure' }),
    ).not.toBeInTheDocument()
    expect(
      within(within(dialog).getByLabelText('Paid from')).queryByText(/Wallet/),
    ).not.toBeInTheDocument()
  })

  it('asks for an account first', async () => {
    mockApi({ accounts: [] })
    renderApp('/transactions')
    await screen.findByText('No transactions in October')
    fireEvent.click(
      screen.getAllByRole('button', { name: 'New transaction' })[0],
    )

    const dialog = await screen.findByRole('dialog', {
      name: 'New transaction',
    })
    expect(
      await within(dialog).findByText('Add an account first'),
    ).toBeVisible()
    fireEvent.click(
      within(dialog).getByRole('link', { name: 'Go to Accounts' }),
    )
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Accounts' }),
    ).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('is in Portuguese too', async () => {
    const { dialog } = await openNewTransaction({
      ...october(),
      accounts: [testAccount({ name: 'Carteira', type: 'CASH' })],
    })
    await act(() => i18n.changeLanguage('pt-PT'))

    expect(
      screen.getByRole('dialog', { name: 'Novo movimento' }),
    ).toBeInTheDocument()
    expect(within(dialog).getByRole('radio', { name: 'Despesa' })).toBeChecked()
    expect(within(dialog).getByLabelText('Pago com')).toHaveDisplayValue(
      `Carteira · 0,00${NBSP}€`,
    )
    expect(
      within(dialog).getByRole('button', { name: 'Adicionar despesa' }),
    ).toBeInTheDocument()
    expect(dialog).toHaveTextContent(
      'Guardado uma só vez, mesmo que toques duas vezes',
    )
  })
})
