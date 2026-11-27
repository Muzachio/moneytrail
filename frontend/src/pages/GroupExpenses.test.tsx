import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '@/i18n/i18n'
import { testExpense, testSettlement } from '@/test/fakeGroupExpenses'
import { testGroup, testMember, type TestGroup } from '@/test/fakeGroups'
import { mockApi, testUser } from '@/test/mockApi'
import { onPhone } from '@/test/phone'
import { renderApp } from '@/test/renderApp'

const networkError = new TypeError('Failed to fetch')

// Ids chosen so their order as text is Rita, you, João: the cents left over go in that order.
const rita = testMember({
  userId: '0199f0a1-0000-7000-8000-000000000001',
  name: 'Rita',
})
const me = testMember({ userId: testUser.id, name: testUser.name })
const joao = testMember({
  userId: '0199f0a3-0000-7000-8000-000000000003',
  name: 'João',
})
const everyone = [rita, me, joao].map(({ userId }) => ({ userId }))

function portoWeekend(overrides: Partial<TestGroup> = {}) {
  return testGroup({
    name: 'Porto weekend',
    members: [rita, me, joao],
    ...overrides,
  })
}

type Sent = [
  path: string,
  init: { params: { header: Record<string, string> }; body: unknown },
]

/** The calls of a POST under the group's expenses, typed: the spy cannot see openapi-fetch's overloads. */
function sent(post: ReturnType<typeof mockApi>['post'], path: string) {
  return (post.mock.calls as unknown as Sent[]).filter(
    ([sentPath]) => sentPath === path,
  )
}

function fill(dialog: HTMLElement, label: string | RegExp, value: string) {
  fireEvent.change(within(dialog).getByLabelText(label), {
    target: { value },
  })
}

/** The dialog's row for someone in "Who takes part". */
function row(dialog: HTMLElement, name: string) {
  return within(dialog).getByRole('checkbox', { name }).closest('li')!
}

async function openNewExpense() {
  // The page header's button; an empty list offers another one below it.
  const [button] = await screen.findAllByRole('button', { name: 'Add expense' })
  fireEvent.click(button)
  return screen.findByRole('dialog', { name: 'New group expense' })
}

describe('group expenses', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    // A Monday: "Today" is 16 November, "Yesterday" the 15th.
    vi.useFakeTimers({
      now: Date.parse('2026-11-16T12:00:00Z'),
      toFake: ['Date'],
    })
  })

  afterEach(async () => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    await act(() => i18n.changeLanguage('en'))
  })

  it('lists the expenses and payments, newest first, with what each is to you', async () => {
    const group = portoWeekend()
    mockApi({
      groups: [group],
      groupExpenses: [
        testExpense(group.id, {
          description: 'Francesinhas dinner',
          amount: 8400,
          paidBy: rita.userId,
          members: everyone,
          occurredOn: '2026-11-14',
        }),
        testExpense(group.id, {
          description: 'Airbnb, 2 nights',
          amount: 24000,
          paidBy: rita.userId,
          category: 'housing',
          method: 'SHARES',
          members: [
            { userId: rita.userId, value: 2 },
            { userId: joao.userId, value: 1 },
          ],
          occurredOn: '2026-11-14',
          edited: true,
        }),
        testExpense(group.id, {
          description: 'Port wine cellar tour',
          amount: 6000,
          paidBy: me.userId,
          category: 'leisure',
          members: everyone,
          occurredOn: '2026-11-15',
        }),
      ],
      settlements: [
        testSettlement(group.id, {
          from: joao.userId,
          to: rita.userId,
          amount: 2000,
          occurredOn: '2026-11-16',
        }),
      ],
    })
    renderApp(`/groups/${group.id}`)

    const list = await screen.findByRole('region', {
      name: 'Expenses and payments',
    })
    const days = within(list).getAllByRole('region')
    expect(days.map((day) => day.getAttribute('aria-label'))).toEqual([
      expect.stringMatching(/^Today, Monday,? 16 November$/),
      expect.stringMatching(/^Yesterday, Sunday,? 15 November$/),
      expect.stringMatching(/^Saturday, 14 November$/),
    ])
    expect(days[0]).toHaveTextContent('João paid Rita')
    expect(days[0]).toHaveTextContent('Payment')
    expect(days[0]).toHaveTextContent('€20.00')
    expect(days[0]).toHaveTextContent('Not involved')

    const tour = within(days[1]).getByRole('link', {
      name: /Port wine cellar tour/,
    })
    expect(tour).toHaveTextContent('Leisure')
    expect(tour).toHaveTextContent('You paid · equally among 3')
    expect(tour).toHaveTextContent('€60.00')
    expect(tour).toHaveTextContent('You lent €40.00')

    // Within a day, the last one recorded comes first.
    const [airbnb, dinner] = within(days[2]).getAllByRole('link')
    expect(dinner).toHaveTextContent('Food')
    expect(dinner).toHaveTextContent('Rita paid · equally among 3')
    expect(dinner).toHaveTextContent('Your share €28.00')
    expect(airbnb).toHaveTextContent('Housing')
    expect(airbnb).toHaveTextContent('Edited')
    expect(airbnb).toHaveTextContent('Rita paid · by shares')
    expect(airbnb).toHaveTextContent('Not involved')
  })

  it('loads the next page with Load more', async () => {
    const group = portoWeekend()
    const expenses = Array.from({ length: 51 }, (_, index) =>
      testExpense(group.id, {
        description: `Coffee ${index + 1}`,
        amount: 150,
        paidBy: me.userId,
        members: everyone,
      }),
    )
    mockApi({ groups: [group], groupExpenses: expenses })
    renderApp(`/groups/${group.id}`)

    const list = await screen.findByRole('region', {
      name: 'Expenses and payments',
    })
    await waitFor(() =>
      expect(within(list).getAllByRole('link')).toHaveLength(50),
    )
    fireEvent.click(within(list).getByRole('button', { name: 'Load more' }))

    await waitFor(() =>
      expect(within(list).getAllByRole('link')).toHaveLength(51),
    )
    // Newest first: the first one recorded is last.
    expect(within(list).getAllByRole('link').at(-1)).toHaveTextContent(
      'Coffee 1',
    )
    expect(within(list).queryByRole('button', { name: 'Load more' })).toBeNull()
  })

  it('offers to add the first expense', async () => {
    const group = portoWeekend()
    mockApi({ groups: [group] })
    renderApp(`/groups/${group.id}`)

    const list = await screen.findByRole('region', {
      name: 'Expenses and payments',
    })
    expect(await within(list).findByText('No expenses yet')).toBeVisible()
    fireEvent.click(within(list).getByRole('button', { name: 'Add expense' }))

    expect(
      await screen.findByRole('dialog', { name: 'New group expense' }),
    ).toBeVisible()
  })

  it('records an expense split equally, with each share shown as it is typed', async () => {
    const group = portoWeekend()
    const { post } = mockApi({
      groups: [group],
      groupExpenses: [
        testExpense(group.id, {
          amount: 900,
          paidBy: rita.userId,
          members: everyone,
        }),
      ],
    })
    renderApp(`/groups/${group.id}`)
    const dialog = await openNewExpense()

    expect(within(dialog).getByText('Porto weekend · EUR')).toBeVisible()
    // You pay, and everyone takes part, until changed.
    expect(within(dialog).getByLabelText('Paid by')).toHaveValue(me.userId)
    expect(within(dialog).getByRole('radio', { name: 'Equally' })).toBeChecked()
    expect(
      within(dialog).getByText('Enter an amount above zero.'),
    ).toBeVisible()
    expect(
      within(dialog).getByRole('button', { name: 'Add expense' }),
    ).toBeDisabled()

    fill(dialog, 'Amount', '10')
    fill(dialog, 'Description', 'Taxi to the airport')
    fireEvent.click(within(dialog).getByRole('radio', { name: 'Transport' }))

    // The cent left over goes to Rita, whose id comes first as text, as the API does it.
    expect(row(dialog, 'Rita')).toHaveTextContent('€3.34')
    expect(row(dialog, 'You')).toHaveTextContent('€3.33')
    expect(row(dialog, 'João')).toHaveTextContent('€3.33')
    expect(within(dialog).getByText('3 people')).toBeVisible()
    expect(within(dialog).getByText('Adds up to €10.00')).toBeVisible()

    fireEvent.click(within(dialog).getByRole('button', { name: 'Add expense' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    const [[, init]] = sent(post, '/v1/groups/{id}/expenses')
    expect(init.params.header['Idempotency-Key']).toEqual(expect.any(String))
    expect(init.body).toEqual({
      amount: { amountMinor: 1000, currency: 'EUR' },
      occurredOn: '2026-11-16',
      description: 'Taxi to the airport',
      category: 'transport',
      paidBy: me.userId,
      split: { method: 'EQUALLY', members: everyone },
    })
    const today = await screen.findByRole('region', { name: /^Today/ })
    expect(
      within(today).getByRole('link', { name: /Taxi to the airport/ }),
    ).toHaveTextContent('You lent €6.67')
  })

  it('splits by percentages, and saves only once they add up to 100 %', async () => {
    const group = portoWeekend()
    const { post } = mockApi({ groups: [group] })
    renderApp(`/groups/${group.id}`)
    await screen.findByText('No expenses yet')
    const dialog = await openNewExpense()
    fill(dialog, 'Amount', '10')
    fill(dialog, 'Description', 'Groceries')
    fireEvent.click(within(dialog).getByRole('radio', { name: 'Food' }))
    fireEvent.click(within(dialog).getByRole('radio', { name: 'Percentages' }))

    expect(
      within(dialog).getByText('Give everyone a percentage above zero.'),
    ).toBeVisible()
    fill(dialog, 'Percentage for you', '50')
    fill(dialog, 'Percentage for Rita', '25,5')
    fill(dialog, 'Percentage for João', '20')
    expect(within(dialog).getByText('4.5% left to assign')).toBeVisible()
    expect(within(dialog).getByText('95.5%')).toBeVisible()
    expect(
      within(dialog).getByRole('button', { name: 'Add expense' }),
    ).toBeDisabled()

    fill(dialog, 'Percentage for João', '30')
    expect(within(dialog).getByText('5.5% too much')).toBeVisible()

    fill(dialog, 'Percentage for João', '24.5')
    expect(within(dialog).getByText('Adds up to €10.00')).toBeVisible()
    expect(row(dialog, 'You')).toHaveTextContent('€5.00')
    expect(row(dialog, 'Rita')).toHaveTextContent('€2.55')
    expect(row(dialog, 'João')).toHaveTextContent('€2.45')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add expense' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    const [[, init]] = sent(post, '/v1/groups/{id}/expenses')
    expect((init.body as { split: unknown }).split).toEqual({
      method: 'PERCENTAGES',
      members: [
        { userId: rita.userId, value: 2550 },
        { userId: me.userId, value: 5000 },
        { userId: joao.userId, value: 2450 },
      ],
    })
  })

  it('splits by exact amounts and by shares, keeping what was typed for each', async () => {
    const group = portoWeekend()
    mockApi({ groups: [group] })
    renderApp(`/groups/${group.id}`)
    await screen.findByText('No expenses yet')
    const dialog = await openNewExpense()
    fill(dialog, 'Amount', '30')
    fireEvent.click(
      within(dialog).getByRole('radio', { name: 'Exact amounts' }),
    )

    fill(dialog, 'Amount for you', '12,50')
    fill(dialog, 'Amount for Rita', '10')
    expect(within(dialog).getByText('€7.50 left to assign')).toBeVisible()
    fill(dialog, 'Amount for João', '9')
    expect(within(dialog).getByText('€1.50 more than the amount')).toBeVisible()
    fill(dialog, 'Amount for João', '7,50')
    expect(within(dialog).getByText('Adds up to €30.00')).toBeVisible()

    // Unticked, João takes no part, and the amounts no longer add up.
    fireEvent.click(within(dialog).getByRole('checkbox', { name: 'João' }))
    expect(row(dialog, 'João')).toHaveTextContent('Not taking part')
    expect(within(dialog).getByText('€7.50 left to assign')).toBeVisible()
    fireEvent.click(within(dialog).getByRole('checkbox', { name: 'João' }))

    fill(dialog, 'Amount', '9,99')
    fireEvent.click(within(dialog).getByRole('radio', { name: 'Shares' }))
    expect(
      within(dialog).getByText(
        'Give everyone a whole number of shares above zero.',
      ),
    ).toBeVisible()
    fill(dialog, 'Shares for you', '2')
    fill(dialog, 'Shares for Rita', '1')
    fill(dialog, 'Shares for João', '1')
    // €9.99 in 4 shares: the two cents left over go to the largest remainders, Rita and João.
    expect(row(dialog, 'You')).toHaveTextContent('€4.99')
    expect(row(dialog, 'Rita')).toHaveTextContent('€2.50')
    expect(row(dialog, 'João')).toHaveTextContent('€2.50')
    expect(within(dialog).getByText('4 shares')).toBeVisible()

    // The exact amounts are still there when coming back to them.
    fireEvent.click(
      within(dialog).getByRole('radio', { name: 'Exact amounts' }),
    )
    expect(within(dialog).getByLabelText('Amount for you')).toHaveValue('12,50')
  })

  it('asks for who takes part and for the category', async () => {
    const group = portoWeekend()
    mockApi({ groups: [group] })
    renderApp(`/groups/${group.id}`)
    await screen.findByText('No expenses yet')
    const dialog = await openNewExpense()
    fill(dialog, 'Amount', '10')
    for (const name of ['You', 'Rita', 'João']) {
      fireEvent.click(within(dialog).getByRole('checkbox', { name }))
    }
    expect(within(dialog).getByText('Choose who takes part.')).toBeVisible()
    fireEvent.click(within(dialog).getByRole('checkbox', { name: 'Rita' }))

    fireEvent.click(within(dialog).getByRole('button', { name: 'Add expense' }))

    expect(await within(dialog).findByText('Pick a category.')).toBeVisible()
    expect(
      within(dialog).getByText('Describe it in a few words.'),
    ).toBeVisible()
  })

  it('keeps one Idempotency-Key while the dialog is open, so a lost answer saves once', async () => {
    const group = portoWeekend()
    const { post, groupExpenses } = mockApi({ groups: [group] })
    renderApp(`/groups/${group.id}`)
    await screen.findByText('No expenses yet')
    const answer = post.getMockImplementation()!
    // Saved by the API, but the answer never arrives.
    post.mockImplementationOnce((async (...args: Parameters<typeof answer>) => {
      await answer(...args)
      throw networkError
    }) as never)
    const dialog = await openNewExpense()
    fill(dialog, 'Amount', '20')
    fill(dialog, 'Description', 'Museum tickets')
    fireEvent.click(within(dialog).getByRole('radio', { name: 'Leisure' }))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add expense' }))
    await within(dialog).findByText(
      'Could not reach MoneyTrail. Check your connection and try again.',
    )

    // A new key here would record the tickets twice.
    fill(dialog, 'Amount', '21')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add expense' }))

    expect(
      await within(dialog).findByText(
        'An earlier attempt was already saved. Close this window and check the list.',
      ),
    ).toBeVisible()
    const keys = sent(post, '/v1/groups/{id}/expenses').map(
      ([, init]) => init.params.header['Idempotency-Key'],
    )
    expect(keys).toHaveLength(2)
    expect(keys[1]).toBe(keys[0])
    expect(groupExpenses.versions).toHaveLength(1)
  })

  it('shows an expense with its split and trail, edits it and reverses it', async () => {
    const group = portoWeekend()
    const airbnb = testExpense(group.id, {
      description: 'Airbnb, 2 nights',
      amount: 24000,
      paidBy: rita.userId,
      category: 'housing',
      method: 'SHARES',
      members: [
        { userId: me.userId, value: 2 },
        { userId: rita.userId, value: 2 },
        { userId: joao.userId, value: 3 },
      ],
    })
    const { post } = mockApi({ groups: [group], groupExpenses: [airbnb] })
    const { router } = renderApp(`/groups/${group.id}`)
    fireEvent.click(
      await screen.findByRole('link', { name: /Airbnb, 2 nights/ }),
    )

    const sheet = await screen.findByRole('dialog', {
      name: 'Airbnb, 2 nights',
    })
    expect(router.state.location.pathname).toBe(
      `/groups/${group.id}/expenses/${airbnb.id}`,
    )
    expect(within(sheet).getByText('Expense · Porto weekend')).toBeVisible()
    expect(
      within(sheet).getByText(
        /Housing · Rita paid · Saturday,? 14 November 2026/,
      ),
    ).toBeVisible()
    expect(
      within(sheet).getByText('Your share').parentElement,
    ).toHaveTextContent('Your share€68.57')
    const split = within(sheet).getByRole('region', { name: 'Split by shares' })
    const rows = within(split)
      .getAllByRole('row')
      .map((each) => each.textContent)
    expect(rows).toEqual([
      'MemberSharesShare',
      'Rita · paid2€68.57',
      'You2€68.57',
      'João3€102.86',
      'Total7€240.00',
    ])
    expect(split).toHaveTextContent(
      '€240.00 does not divide exactly. The cent left over goes to João, whose share had the largest remainder, so the shares add up to €240.00.',
    )
    const trail = within(sheet).getByRole('region', { name: 'Trail' })
    expect(trail).toHaveTextContent('Recorded as €240.00 · Housing')
    expect(trail).toHaveTextContent(/Rita · /)

    // Edit: the dialog starts filled in, and saves as a reversal with a replacement.
    fireEvent.click(within(sheet).getByRole('button', { name: 'Edit' }))
    const dialog = await screen.findByRole('dialog', {
      name: 'Edit group expense',
    })
    expect(within(dialog).getByLabelText('Amount')).toHaveValue('240.00')
    expect(within(dialog).getByRole('radio', { name: 'Shares' })).toBeChecked()
    expect(within(dialog).getByLabelText('Shares for João')).toHaveValue('3')
    expect(within(dialog).getByRole('radio', { name: 'Housing' })).toBeChecked()
    fill(dialog, 'Amount', '250')
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Save changes' }),
    )

    await waitFor(() =>
      expect(
        screen.queryByRole('dialog', { name: 'Edit group expense' }),
      ).toBeNull(),
    )
    const [[, init]] = sent(
      post,
      '/v1/groups/{id}/expenses/{expenseId}/reverse',
    )
    expect(init.body).toEqual({
      replacement: expect.objectContaining({
        amount: { amountMinor: 25000, currency: 'EUR' },
        split: {
          method: 'SHARES',
          members: [
            { userId: rita.userId, value: 2 },
            { userId: me.userId, value: 2 },
            { userId: joao.userId, value: 3 },
          ],
        },
      }),
    })
    // The panel follows the correction, a new entry with its own id.
    await waitFor(() =>
      expect(router.state.location.pathname).not.toContain(airbnb.id),
    )
    const corrected = await screen.findByRole('dialog', {
      name: 'Airbnb, 2 nights',
    })
    expect(await within(corrected).findByText('Edited by you')).toBeVisible()
    const newTrail = within(corrected).getByRole('region', { name: 'Trail' })
    expect(newTrail).toHaveTextContent('Corrected to €250.00 · Housing')
    expect(newTrail).toHaveTextContent('Original reversed')

    // Reverse: asked first, then the expense no longer counts.
    fireEvent.click(within(corrected).getByRole('button', { name: 'Reverse' }))
    const confirm = within(corrected).getByRole('group', {
      name: 'Reverse this expense?',
    })
    fireEvent.click(
      within(confirm).getByRole('button', { name: 'Reverse expense' }),
    )

    expect(
      await within(corrected).findByText(
        "Reversed. It no longer counts towards anyone's balance.",
      ),
    ).toBeVisible()
    expect(within(corrected).queryByRole('button', { name: 'Edit' })).toBeNull()
    expect(
      sent(post, '/v1/groups/{id}/expenses/{expenseId}/reverse'),
    ).toHaveLength(2)
  })

  it('adds an expense from the centre button on a phone, but not to an archived group', async () => {
    onPhone()
    const group = portoWeekend()
    const archived = portoWeekend({ name: 'Lisbon trip', archived: true })
    mockApi({ groups: [group, archived] })
    const { router } = renderApp(`/groups/${group.id}`)

    fireEvent.click(
      await screen.findByRole('button', {
        name: 'Add an expense to Porto weekend',
      }),
    )
    expect(
      await screen.findByRole('dialog', { name: 'New group expense' }),
    ).toBeVisible()
    fireEvent.keyDown(document.activeElement!, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())

    await act(() => router.navigate(`/groups/${archived.id}`))
    await screen.findByRole('heading', { level: 1, name: 'Lisbon trip' })
    expect(screen.queryByRole('button', { name: 'Add expense' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'New transaction' }))
    expect(
      await screen.findByRole('dialog', { name: 'New transaction' }),
    ).toBeVisible()
  })

  it('speaks European Portuguese', async () => {
    await act(() => i18n.changeLanguage('pt-PT'))
    const group = portoWeekend()
    mockApi({
      groups: [group],
      groupExpenses: [
        testExpense(group.id, {
          description: 'Jantar',
          amount: 8400,
          paidBy: rita.userId,
          members: everyone,
          occurredOn: '2026-11-14',
        }),
      ],
    })
    renderApp(`/groups/${group.id}`)

    const list = await screen.findByRole('region', {
      name: 'Despesas e pagamentos',
    })
    const dinner = await within(list).findByRole('link', { name: /Jantar/ })
    expect(dinner).toHaveTextContent('Rita pagou · por igual entre 3')
    // Testing Library reads the no-break space Intl puts before the euro sign as a space.
    expect(dinner).toHaveTextContent('A tua parte 28,00 €')

    fireEvent.click(screen.getByRole('button', { name: 'Adicionar despesa' }))
    const dialog = await screen.findByRole('dialog', {
      name: 'Nova despesa do grupo',
    })
    fill(dialog, 'Valor', '10')
    fireEvent.click(within(dialog).getByRole('radio', { name: 'Percentagens' }))
    fill(dialog, 'Percentagem para ti', '50')
    fill(dialog, 'Percentagem para Rita', '25')
    fill(dialog, 'Percentagem para João', '20')
    expect(within(dialog).getByText('Falta atribuir 5%')).toBeVisible()
    fill(dialog, 'Percentagem para João', '25')
    expect(within(dialog).getByText('Soma 10,00 €')).toBeVisible()
  })
})
