import { act, fireEvent, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '@/i18n/i18n'
import { euros, mockApi, testTransaction } from '@/test/mockApi'
import { OCTOBER_25, october } from '@/test/october'
import { renderApp } from '@/test/renderApp'

function day(name: RegExp) {
  return screen.getByRole('region', { name })
}

/** The query of every `GET /v1/transactions` sent so far. */
function listQueries(get: ReturnType<typeof mockApi>['get']) {
  type Call = [string, { params: { query: Record<string, unknown> } }]
  return (get.mock.calls as unknown as Call[])
    .filter(([path]) => path === '/v1/transactions')
    .map(([, init]) => init.params.query)
}

describe('TransactionsPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    // Only the date: React Query, the search's pause and waitFor keep real timers.
    vi.useFakeTimers({ now: OCTOBER_25, toFake: ['Date'] })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  function render(path = '/transactions') {
    const data = october()
    const api = mockApi(data)
    renderApp(path)
    return { ...data, ...api }
  }

  it("shows the month's count and totals, without transfers in them", async () => {
    render()

    const summary = await screen.findByText('9 transactions in October')
    expect(summary.parentElement).toHaveTextContent(
      'Income +€1,874.99Spending −€149.42Net +€1,725.57',
    )
    expect(
      screen.getByRole('heading', { level: 1, name: 'Transactions' }),
    ).toBeInTheDocument()
    expect(screen.getByText('October 2026')).toBeInTheDocument()
  })

  it('groups the transactions by day, newest first, each day with its net', async () => {
    render()

    const today = await screen.findByRole('region', {
      name: 'Today, Sunday, 25 October',
    })
    expect(today).toHaveTextContent('Net for the day: −€18.40')
    expect(within(today).getByRole('link')).toHaveTextContent(
      'BrunchFood·Credit card−€18.40',
    )

    const yesterday = day(/^Yesterday, Saturday, 24 October/)
    expect(yesterday).toHaveTextContent('−€53.72')
    expect(
      within(yesterday)
        .getAllByRole('link')
        .map((link) => link.textContent),
    ).toEqual([
      'GroceriesFood·Everyday checking−€38.72',
      'Cinema ticketsLeisure·Credit card−€15.00',
    ])

    // The transfer adds nothing to the day's net, and its amount has no sign.
    const friday = day(/^Friday, 23 October/)
    expect(friday).toHaveTextContent('Net for the day: −€40.00')
    expect(within(friday).getAllByRole('link')[0]).toHaveTextContent(
      'Emergency fund top-upTransfer·Everyday checking → Emergency fund€200.00',
    )

    const tuesday = day(/^Tuesday, 20 October/)
    expect(tuesday).toHaveTextContent('Net for the day: +€15.19')
    expect(within(tuesday).getAllByRole('link')[0]).toHaveTextContent(
      'Headphones refundRefunds·Everyday checking+€24.99',
    )
    const list = screen.getByRole('region', { name: 'October transactions' })
    expect(
      within(list)
        .getAllByRole('region')
        .map((region) => region.getAttribute('aria-label')),
    ).toEqual([
      'Today, Sunday, 25 October',
      'Yesterday, Saturday, 24 October',
      'Friday, 23 October',
      'Thursday, 22 October',
      'Tuesday, 20 October',
      'Thursday, 1 October',
    ])
  })

  it('marks edited transactions', async () => {
    render()

    const thursday = await screen.findByRole('region', {
      name: 'Thursday, 22 October',
    })
    expect(within(thursday).getByRole('link')).toHaveTextContent(
      'Dinner outEdited',
    )
    expect(within(day(/^Friday/)).queryByText('Edited')).not.toBeInTheDocument()
  })

  it('moves to earlier months, but not past the current one', async () => {
    const { get } = render()
    await screen.findByText('9 transactions in October')

    expect(screen.getByRole('button', { name: 'Next month' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Previous month' }))

    expect(await screen.findByText('September 2026')).toBeInTheDocument()
    expect(
      await screen.findByText('No transactions in September'),
    ).toBeInTheDocument()
    expect(screen.getByText('0 transactions in September')).toBeInTheDocument()
    expect(listQueries(get).at(-1)).toMatchObject({ month: '2026-09' })

    fireEvent.click(screen.getByRole('button', { name: 'Next month' }))
    expect(
      await screen.findByText('9 transactions in October'),
    ).toBeInTheDocument()
  })

  it('offers a new transaction when the month has none', async () => {
    mockApi(october())
    renderApp('/transactions?month=2026-08')

    await screen.findByText('No transactions in August')
    const buttons = screen.getAllByRole('button', { name: 'New transaction' })
    // The header's, and the empty list's.
    expect(buttons).toHaveLength(2)
    fireEvent.click(buttons[1])
    expect(
      await screen.findByRole('dialog', { name: 'New transaction' }),
    ).toBeInTheDocument()
  })

  it('filters by type, account and category, and keeps the filters in the address', async () => {
    const { get, card, category } = render()
    await screen.findByText('9 transactions in October')

    const types = screen.getByRole('group', { name: 'Type' })
    expect(within(types).getByRole('button', { name: 'All' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    fireEvent.click(within(types).getByRole('button', { name: 'Expenses' }))
    expect(
      await screen.findByText('6 transactions in October'),
    ).toBeInTheDocument()
    expect(screen.queryByText('Headphones refund')).not.toBeInTheDocument()
    expect(listQueries(get).at(-1)).toMatchObject({ type: 'EXPENSE' })

    fireEvent.change(screen.getByRole('combobox', { name: 'Account' }), {
      target: { value: card.id },
    })
    expect(
      await screen.findByText('3 transactions in October'),
    ).toBeInTheDocument()
    expect(listQueries(get).at(-1)).toMatchObject({
      type: 'EXPENSE',
      accountId: card.id,
    })

    fireEvent.change(screen.getByRole('combobox', { name: 'Category' }), {
      target: { value: category('food').id },
    })
    expect(
      await screen.findByText('2 transactions in October'),
    ).toBeInTheDocument()
    expect(listQueries(get).at(-1)).toMatchObject({
      categoryId: category('food').id,
    })
    expect(screen.getAllByRole('link').map((link) => link.textContent)).toEqual(
      expect.arrayContaining([expect.stringContaining('Brunch')]),
    )
  })

  it('reads the filters from the address', async () => {
    const data = october()
    const { get } = mockApi(data)
    renderApp(`/transactions?type=TRANSFER&account=${data.emergency.id}`)

    expect(
      await screen.findByText('1 transaction in October'),
    ).toBeInTheDocument()
    expect(listQueries(get)[0]).toEqual({
      month: '2026-10',
      type: 'TRANSFER',
      accountId: data.emergency.id,
      limit: 50,
    })
    expect(screen.getByRole('button', { name: 'Transfers' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    expect(screen.getByRole('combobox', { name: 'Account' })).toHaveValue(
      data.emergency.id,
    )
  })

  it('searches the descriptions once typing pauses, and says when nothing matches', async () => {
    const { get } = render()
    await screen.findByText('9 transactions in October')
    const search = screen.getByRole('searchbox', {
      name: 'Search transactions',
    })

    fireEvent.change(search, { target: { value: 'g' } })
    fireEvent.change(search, { target: { value: 'gro' } })
    expect(await screen.findByText('1 transaction in October')).toBeVisible()
    // One request for the pause, not one per letter.
    expect(listQueries(get).filter((query) => query.q !== undefined)).toEqual([
      expect.objectContaining({ q: 'gro' }),
    ])

    fireEvent.change(search, { target: { value: 'nothing like this' } })
    expect(await screen.findByText('No transactions match')).toBeVisible()
    expect(
      screen.getByText('Try another word or clear the filters.'),
    ).toBeVisible()

    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }))
    expect(
      await screen.findByText('9 transactions in October'),
    ).toBeInTheDocument()
    expect(search).toHaveValue('')
  })

  it('loads more transactions a page at a time', async () => {
    const data = october()
    const many = Array.from({ length: 52 }, (_, index) =>
      testTransaction({
        description: `Coffee ${index + 1}`,
        occurredOn: '2026-10-10',
        categoryId: data.category('food').id,
        accountId: data.wallet.id,
        amount: euros(120),
      }),
    )
    mockApi({ ...data, transactions: [...data.transactions, ...many] })
    renderApp('/transactions')

    expect(
      await screen.findByText('Showing 50 of 61 transactions'),
    ).toBeInTheDocument()
    // The 10th goes on in the next page: its net would only be part of the day's.
    expect(day(/^Saturday, 10 October/)).not.toHaveTextContent(
      'Net for the day',
    )
    fireEvent.click(screen.getByRole('button', { name: 'Load more' }))

    expect(
      await screen.findByText('Showing 61 of 61 transactions'),
    ).toBeInTheDocument()
    expect(day(/^Saturday, 10 October/)).toHaveTextContent(
      'Net for the day: −€62.40',
    )
    expect(
      screen.queryByRole('button', { name: 'Load more' }),
    ).not.toBeInTheDocument()
  })

  it('says the list is loading until it arrives', async () => {
    const { get } = render()
    const answer = get.getMockImplementation()!
    let release = () => {}
    const held = new Promise<void>((resolve) => {
      release = resolve
    })
    get.mockImplementation((async (path: string, init: never) => {
      if (path === '/v1/transactions') {
        await held
      }
      return (answer as (...args: unknown[]) => unknown)(path, init)
    }) as never)

    expect(
      await screen.findByText('Loading your transactions…'),
    ).toHaveAttribute('role', 'status')
    release()
    expect(
      await screen.findByText('9 transactions in October'),
    ).toBeInTheDocument()
    expect(
      screen.queryByText('Loading your transactions…'),
    ).not.toBeInTheDocument()
  })

  it('says when the list could not be loaded, and tries again', async () => {
    const { get } = render()
    const answer = get.getMockImplementation()!
    get.mockImplementation(((path: string, init: never) =>
      path === '/v1/transactions'
        ? Promise.reject(new TypeError('Failed to fetch'))
        : (answer as (...args: unknown[]) => unknown)(path, init)) as never)

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Could not load your transactions.',
    )
    get.mockImplementation(answer)
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(
      await screen.findByText('9 transactions in October'),
    ).toBeInTheDocument()
  })

  it('shows the month in Portuguese', async () => {
    // toHaveTextContent reads Intl's no-break spaces (U+00A0) as plain ones.
    render()
    await act(() => i18n.changeLanguage('pt-PT'))

    const summary = await screen.findByText('9 movimentos em outubro')
    expect(summary.parentElement).toHaveTextContent(
      `Receitas +1874,99 €Despesas −149,42 €Saldo +1725,57 €`,
    )
    const today = screen.getByRole('region', {
      name: 'Hoje, domingo, 25 de outubro',
    })
    expect(within(today).getByRole('link')).toHaveTextContent(
      `BrunchAlimentação·Credit card−18,40 €`,
    )
    expect(
      screen.getByRole('region', { name: 'Sexta-feira, 23 de outubro' }),
    ).toHaveTextContent('Transferência')
    expect(screen.getByText('Editado')).toBeInTheDocument()
  })
})
