import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '@/i18n/i18n'
import {
  euros,
  mockApi,
  testAccount,
  testCategory,
  testTransaction,
} from '@/test/mockApi'
import { OCTOBER_25, october } from '@/test/october'
import { onPhone } from '@/test/phone'
import { renderApp } from '@/test/renderApp'

// Intl separates Portuguese amounts and the euro sign with a no-break space (U+00A0).
const NBSP = ' '

function region(name: string | RegExp) {
  return screen.getByRole('region', { name })
}

/** The text of each item of the list in `container`. */
function items(container: HTMLElement) {
  return within(container)
    .getAllByRole('listitem')
    .map((item) => item.textContent)
}

type Query = { from: string; to: string; groupBy: string }

/** What the in-memory API answers, to answer the same around a request a test holds or fails. */
function realApi(get: ReturnType<typeof mockApi>['get']) {
  return get.getMockImplementation() as unknown as (
    path: string,
    init: unknown,
  ) => Promise<unknown>
}

/** The query of every `GET /v1/reports/spending` sent so far. */
function reportQueries(get: ReturnType<typeof mockApi>['get']) {
  type Call = [string, { params: { query: Query } }]
  return (get.mock.calls as unknown as Call[])
    .filter(([path]) => path === '/v1/reports/spending')
    .map(([, init]) => init.params.query)
}

/**
 * The design canvas' October, with a September before it: the salary and the rent, so that the
 * trend has two months to show.
 */
function data() {
  const month = october()
  const earlier = (
    description: string,
    day: string,
    key: string,
    amountMinor: number,
    type: 'EXPENSE' | 'INCOME' = 'EXPENSE',
  ) =>
    testTransaction({
      type,
      description,
      occurredOn: day,
      recordedAt: `${day}T09:00:00Z`,
      categoryId: month.category(key).id,
      accountId: month.checking.id,
      amount: euros(amountMinor),
    })
  return {
    ...month,
    transactions: [
      earlier('Salary', '2026-09-01', 'salary', 185000, 'INCOME'),
      earlier('Rent', '2026-09-02', 'housing', 75000),
      ...month.transactions,
    ],
  }
}

describe('SummaryPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    // Only the date: React Query and waitFor keep real timers.
    vi.useFakeTimers({ now: OCTOBER_25, toFake: ['Date'] })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  function render(path = '/', seed = data()) {
    const api = mockApi(seed)
    renderApp(path)
    return { ...seed, ...api }
  }

  describe('on a wide screen', () => {
    it("shows the month's income, spending and net, transfers left out", async () => {
      render()

      const income = await screen.findByRole('region', { name: 'Income' })
      expect(income).toHaveTextContent('€1,874.99')
      expect(income).toHaveTextContent('1–25 October')
      expect(region('Spending')).toHaveTextContent(
        '€149.428% of income · 9 transactions',
      )
      expect(region('Net this month')).toHaveTextContent(
        '+€1,725.57You kept 92% of your income',
      )
      expect(
        screen.getByText('Where your money went this month.'),
      ).toBeVisible()
    })

    it('asks the reports for this month up to today, and six months for the trend', async () => {
      const { get } = render()

      await screen.findByRole('region', { name: 'Spending by category' })
      expect(reportQueries(get)).toEqual(
        expect.arrayContaining([
          { from: '2026-10-01', to: '2026-10-25', groupBy: 'category' },
          { from: '2026-05-01', to: '2026-10-25', groupBy: 'month' },
        ]),
      )
    })

    it("lists the month's spending by category, largest first, with each share", async () => {
      render()

      const categories = await screen.findByRole('region', {
        name: 'Spending by category',
      })
      expect(categories).toHaveTextContent('1–25 October · €149.42 in total')
      // The shares are rounded half up and still add up to 100%.
      expect(items(categories)).toEqual([
        'Food€94.42, 63% of spending',
        'Transport€40.00, 27% of spending',
        'Leisure€15.00, 10% of spending',
      ])
      expect(
        within(categories).getByRole('link', { name: 'See transactions' }),
      ).toHaveAttribute('href', '/transactions')
    })

    it("draws each category with the icon the API gives it, and the person's own with the one picked for it", async () => {
      const seed = data()
      const pets = testCategory('EXPENSE', { name: 'Pets', icon: 'paw-print' })
      seed.categories.push(pets)
      seed.transactions.push(
        testTransaction({
          description: 'Cat food',
          occurredOn: '2026-10-25',
          recordedAt: '2026-10-25T20:00:00Z',
          categoryId: pets.id,
          accountId: seed.checking.id,
          amount: euros(1200),
        }),
      )
      render('/', seed)

      const categories = await screen.findByRole('region', {
        name: 'Spending by category',
      })
      const drawn = (container: HTMLElement, text: string) =>
        within(container).getByText(text).closest('li')!.querySelector('svg')
      expect(drawn(categories, 'Pets')).toHaveClass('lucide-paw-print')
      expect(drawn(categories, 'Food')).toHaveClass('lucide-utensils')
      const recent = region('Recent transactions')
      expect(drawn(recent, 'Cat food')).toHaveClass('lucide-paw-print')
      expect(drawn(recent, 'Brunch')).toHaveClass('lucide-utensils')
    })

    it('shows the six-month trend with a table that says it in words', async () => {
      render()

      const trend = await screen.findByRole('region', { name: 'Last 6 months' })
      // The chart's legend names each colour and gives the month in view in text.
      expect(trend).toHaveTextContent(
        'October 2026 · to dateIncome€1,874.99Spending€149.42Net+€1,725.57',
      )
      // The table is there for screen readers while the chart shows.
      const table = within(trend).getByRole('table', {
        name: 'Income and spending in the last 6 months',
      })
      expect(
        within(table)
          .getAllByRole('row')
          .map((row) => row.textContent),
      ).toEqual([
        'MonthIncomeSpendingNet',
        'May€0.00€0.00€0.00',
        'June€0.00€0.00€0.00',
        'July€0.00€0.00€0.00',
        'August€0.00€0.00€0.00',
        'September€1,850.00€750.00+€1,100.00',
        'October (to date)€1,874.99€149.42+€1,725.57',
      ])

      const view = within(trend).getByRole('group', { name: 'View' })
      const chart = within(view).getByRole('button', { name: 'Chart' })
      expect(chart).toHaveAttribute('aria-pressed', 'true')
      fireEvent.click(within(view).getByRole('button', { name: 'Table' }))
      expect(
        within(view).getByRole('button', { name: 'Table' }),
      ).toHaveAttribute('aria-pressed', 'true')
      // The table in sight, the chart and its legend gone.
      expect(within(trend).queryByText('October 2026 · to date')).toBeNull()
      expect(within(trend).getByRole('table')).toBeInTheDocument()
    })

    it('lists the latest six transactions, each opening its detail', async () => {
      const { named } = render()

      const recent = await screen.findByRole('region', {
        name: 'Recent transactions',
      })
      const links = within(recent).getAllByRole('link', {
        name: (name) => name !== 'View all',
      })
      expect(links.map((link) => link.textContent)).toEqual([
        'BrunchFood · Credit card−€18.40Today',
        'GroceriesFood · Everyday checking−€38.72Yesterday',
        'Cinema ticketsLeisure · Credit card−€15.00Yesterday',
        'Emergency fund top-upTransfer · Everyday checking → Emergency fund€200.00Fri, 23 Oct',
        'Navegante monthly passTransport · Everyday checking−€40.00Fri, 23 Oct',
        'Dinner outEditedFood · Credit card−€27.50Thu, 22 Oct',
      ])
      expect(links[0]).toHaveAttribute(
        'href',
        `/transactions/${named('Brunch').id}`,
      )
      expect(
        within(recent).getByRole('link', { name: 'View all' }),
      ).toHaveAttribute('href', '/transactions')
    })

    it('shows the balance of each account and the net worth', async () => {
      render()

      const accounts = await screen.findByRole('region', { name: 'Accounts' })
      expect(items(accounts)).toEqual([
        'Everyday checkingCheckingBalance: €2,184.37',
        'Emergency fundSavingsBalance: €6,500.00',
        'WalletCashBalance: €45.20',
        'Credit cardCredit card · what you oweBalance: −€312.40',
      ])
      expect(accounts).toHaveTextContent('Net worth€8,417.17')
      expect(
        within(accounts).getByRole('link', { name: 'Manage' }),
      ).toHaveAttribute('href', '/accounts')
    })

    it('goes back a month, with the whole month and the trend that ends with it', async () => {
      const { get } = render()
      await screen.findByRole('region', { name: 'Net this month' })

      fireEvent.click(screen.getByRole('button', { name: 'Previous month' }))

      const net = await screen.findByRole('region', {
        name: 'Net in September',
      })
      expect(net).toHaveTextContent('+€1,100.00You kept 59% of your income')
      expect(region('Spending')).toHaveTextContent(
        '€750.0041% of income · 2 transactions',
      )
      expect(region('Income')).toHaveTextContent('1–30 September')
      expect(items(region('Spending by category'))).toEqual([
        'Housing€750.00, 100% of spending',
      ])
      expect(
        screen.getByText('Where your money went in September 2026.'),
      ).toBeVisible()
      expect(reportQueries(get)).toEqual(
        expect.arrayContaining([
          { from: '2026-09-01', to: '2026-09-30', groupBy: 'category' },
          { from: '2026-04-01', to: '2026-09-30', groupBy: 'month' },
        ]),
      )
      // Its transactions open over the transactions page at that month.
      expect(
        within(region('Recent transactions')).getByRole('link', {
          name: 'View all',
        }),
      ).toHaveAttribute('href', '/transactions?month=2026-09')
    })

    it('says so when a month before had nothing', async () => {
      render('/?month=2026-08')

      const categories = await screen.findByRole('region', {
        name: 'Spending by category',
      })
      expect(categories).toHaveTextContent('Nothing spent in August.')
      expect(region('Recent transactions')).toHaveTextContent(
        'No transactions in August.',
      )
      expect(region('Spending')).toHaveTextContent(
        '€0.00No income to compare with · 0 transactions',
      )
      expect(region('Net in August')).toHaveTextContent(
        '€0.00Nothing came in or went out',
      )
    })

    it('adds a transaction recorded from the header to the summary', async () => {
      render()
      const spending = await screen.findByRole('region', { name: 'Spending' })
      expect(spending).toHaveTextContent('€149.42')

      fireEvent.click(screen.getByRole('button', { name: 'New transaction' }))
      const dialog = await screen.findByRole('dialog', {
        name: 'New transaction',
      })
      fireEvent.change(within(dialog).getByLabelText('Amount'), {
        target: { value: '12,34' },
      })
      fireEvent.change(within(dialog).getByLabelText('Description'), {
        target: { value: 'Bakery' },
      })
      fireEvent.click(within(dialog).getByRole('radio', { name: 'Food' }))
      fireEvent.click(
        within(dialog).getByRole('button', { name: 'Add expense' }),
      )

      await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
      await waitFor(() =>
        expect(region('Spending')).toHaveTextContent('€161.76'),
      )
      expect(items(region('Spending by category'))[0]).toBe(
        'Food€106.76, 66% of spending',
      )
      const recent = within(region('Recent transactions')).getAllByRole(
        'link',
        {
          name: (name) => name !== 'View all',
        },
      )
      expect(recent[0]).toHaveTextContent(
        'BakeryFood · Everyday checking−€12.34Today',
      )
    })

    it('is in Portuguese for Portuguese readers', async () => {
      await act(() => i18n.changeLanguage('pt-PT'))
      render()

      const categories = await screen.findByRole('region', {
        name: 'Despesas por categoria',
      })
      // toHaveTextContent turns every kind of space into a plain one.
      expect(categories).toHaveTextContent(
        '1–25 de outubro · 149,42 € no total',
      )
      expect(items(categories)[0]).toBe(
        `Alimentação94,42${NBSP}€, 63% das despesas`,
      )
      expect(region('Saldo deste mês')).toHaveTextContent(
        'Ficaste com 92% das tuas receitas',
      )
      expect(
        within(region('Últimos 6 meses')).getByRole('table', {
          name: 'Receitas e despesas nos últimos 6 meses',
        }),
      ).toHaveTextContent('outubro (até hoje)1874,99 €')
      expect(region('Contas')).toHaveTextContent('Património líquido8417,17 €')
    })
  })

  describe('on a phone', () => {
    beforeEach(() => {
      onPhone()
    })

    it('shows what was spent and kept, the top categories and the latest transactions', async () => {
      render()

      const spent = await screen.findByRole('region', {
        name: 'Spent in October',
      })
      expect(spent).toHaveTextContent(
        '€149.428% of €1,874.99 income+€1,725.57 kept',
      )
      expect(items(region('Top categories'))).toEqual([
        'Food€94.42',
        'Transport€40.00',
        'Leisure€15.00',
      ])
      const recent = region('Recent')
      const links = within(recent).getAllByRole('link', {
        name: (name) => name !== 'See all',
      })
      expect(links.map((link) => link.textContent)).toEqual([
        'BrunchToday · Credit card−€18.40',
        'GroceriesYesterday · Everyday checking−€38.72',
        'Cinema ticketsYesterday · Credit card−€15.00',
      ])
      expect(
        within(recent).getByRole('link', { name: 'See all' }),
      ).toHaveAttribute('href', '/transactions')
      // The month's name alone, as on the phone in the design canvas.
      expect(screen.getByText('October')).toBeInTheDocument()
      // Shorter than on a wide screen: no trend and no accounts.
      expect(screen.queryByRole('region', { name: 'Last 6 months' })).toBeNull()
      expect(screen.queryByRole('region', { name: 'Accounts' })).toBeNull()
    })

    it('shows the four top categories, and all of them on request', async () => {
      const seed = data()
      const more = (description: string, key: string, amountMinor: number) =>
        testTransaction({
          description,
          occurredOn: '2026-10-21',
          categoryId: seed.category(key).id,
          accountId: seed.checking.id,
          amount: euros(amountMinor),
        })
      seed.transactions.push(
        more('Electricity', 'bills', 8815),
        more('Shoes', 'shopping', 6799),
      )
      render('/', seed)

      const top = await screen.findByRole('region', { name: 'Top categories' })
      expect(items(top)).toEqual([
        'Food€94.42',
        'Bills€88.15',
        'Shopping€67.99',
        'Transport€40.00',
      ])
      const seeAll = within(top).getByRole('button', { name: 'See all' })
      expect(seeAll).toHaveAttribute('aria-expanded', 'false')

      fireEvent.click(seeAll)
      expect(items(top)).toHaveLength(5)
      expect(
        within(top).getByRole('button', { name: 'See fewer' }),
      ).toHaveAttribute('aria-expanded', 'true')
    })

    it('says when more went out than came in', async () => {
      const seed = data()
      seed.transactions.push(
        testTransaction({
          description: 'New laptop',
          occurredOn: '2026-10-21',
          categoryId: seed.category('shopping').id,
          accountId: seed.card.id,
          amount: euros(200000),
        }),
      )
      render('/', seed)

      expect(
        await screen.findByRole('region', { name: 'Spent in October' }),
      ).toHaveTextContent(
        '€2,149.42115% of €1,874.99 income€274.43 over income',
      )
    })
  })

  describe('while loading, on errors and before the first transaction', () => {
    it('says it is loading until the reports arrive', async () => {
      const { get } = render()
      const answer = realApi(get)
      let release = () => {}
      const arrived = new Promise<void>((resolve) => (release = resolve))
      get.mockImplementation((async (path: string, init: unknown) => {
        if (path === '/v1/reports/spending') {
          await arrived
        }
        return answer(path, init)
      }) as never)

      expect(await screen.findByRole('status')).toHaveTextContent(
        'Loading your summary…',
      )
      release()
      expect(
        await screen.findByRole('region', { name: 'Spending by category' }),
      ).toBeInTheDocument()
      expect(screen.queryByRole('status')).toBeNull()
    })

    it('says when the summary could not load, and tries again', async () => {
      const seed = data()
      const { get } = mockApi(seed)
      const answer = realApi(get)
      let failing = true
      get.mockImplementation((async (path: string, init: unknown) =>
        failing && path === '/v1/reports/spending'
          ? {
              error: { title: 'Error', status: 503, code: 'unavailable' },
              response: new Response(null, { status: 503 }),
            }
          : answer(path, init)) as never)
      renderApp('/')

      const alert = await screen.findByRole('alert')
      expect(alert).toHaveTextContent('Could not load your summary.')
      failing = false
      fireEvent.click(within(alert).getByRole('button', { name: 'Try again' }))

      expect(
        await screen.findByRole('region', { name: 'Spending by category' }),
      ).toBeInTheDocument()
      expect(screen.queryByRole('alert')).toBeNull()
    })

    it('shows a first step to someone with no accounts and no transactions yet', async () => {
      render('/', { ...data(), accounts: [], transactions: [] })

      const empty = await screen.findByRole('region', {
        name: 'Nothing to sum up yet',
      })
      expect(empty).toHaveTextContent(
        'Start with the accounts your money is in.',
      )
      expect(
        within(empty).getByRole('link', { name: 'Add an account' }),
      ).toHaveAttribute('href', '/accounts')
      expect(
        screen.queryByRole('region', { name: 'Spending by category' }),
      ).toBeNull()
      expect(screen.queryByRole('region', { name: 'Accounts' })).toBeNull()
    })

    it('offers the first transaction to someone with an account, and shows its balance', async () => {
      render('/', {
        ...data(),
        accounts: [
          testAccount({ name: 'Everyday checking', balance: euros(50000) }),
        ],
        transactions: [],
      })

      const empty = await screen.findByRole('region', {
        name: 'Nothing to sum up yet',
      })
      expect(region('Accounts')).toHaveTextContent('Net worth€500.00')
      fireEvent.click(
        within(empty).getByRole('button', { name: 'New transaction' }),
      )
      expect(
        await screen.findByRole('dialog', { name: 'New transaction' }),
      ).toBeInTheDocument()
    })

    it('shows the first step on a phone too, in Portuguese', async () => {
      onPhone()
      await act(() => i18n.changeLanguage('pt-PT'))
      render('/', { ...data(), accounts: [], transactions: [] })

      const empty = await screen.findByRole('region', {
        name: 'Ainda não há nada para resumir',
      })
      expect(
        within(empty).getByRole('link', { name: 'Adicionar conta' }),
      ).toHaveAttribute('href', '/accounts')
    })
  })
})
