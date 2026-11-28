import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '@/i18n/i18n'
import { testExpense, testSettlement } from '@/test/fakeGroupExpenses'
import { testGroup, testMember, type TestGroup } from '@/test/fakeGroups'
import { testActivity } from '@/test/fakeSettlements'
import { mockApi, testUser } from '@/test/mockApi'
import { renderApp } from '@/test/renderApp'

const networkError = new TypeError('Failed to fetch')

// Ids chosen so their order as text is Rita, you, João.
const rita = testMember({
  userId: '0199f0a1-0000-7000-8000-000000000001',
  name: 'Rita',
})
const me = testMember({ userId: testUser.id, name: testUser.name })
const joao = testMember({
  userId: '0199f0a3-0000-7000-8000-000000000003',
  name: 'João',
})
const pedro = { userId: '0199f0a4-0000-7000-8000-000000000004', name: 'Pedro' }

function portoWeekend(overrides: Partial<TestGroup> = {}) {
  return testGroup({
    name: 'Porto weekend',
    members: [rita, me, joao],
    ...overrides,
  })
}

/**
 * Rita paid €90.00 for the three of them, and you €12.00 for you and João: Rita is owed €60.00,
 * you owe €24.00 and João €36.00. The greedy suggestions: João pays Rita €36.00, then you pay
 * Rita €24.00.
 */
function weekendExpenses(groupId: string) {
  return [
    testExpense(groupId, {
      description: 'Francesinhas dinner',
      amount: 9000,
      paidBy: rita.userId,
      members: [rita, me, joao].map(({ userId }) => ({ userId })),
    }),
    testExpense(groupId, {
      description: 'Coffee',
      amount: 1200,
      paidBy: me.userId,
      members: [me, joao].map(({ userId }) => ({ userId })),
    }),
  ]
}

type Sent = [
  path: string,
  init: { params: { header: Record<string, string> }; body: unknown },
]

/** The calls of a POST, typed: the spy cannot see openapi-fetch's overloads. */
function sent(post: ReturnType<typeof mockApi>['post'], path: string) {
  return (post.mock.calls as unknown as Sent[]).filter(
    ([sentPath]) => sentPath === path,
  )
}

function balanceRows() {
  return within(screen.getByRole('region', { name: 'Balances' }))
    .getAllByRole('listitem')
    .map((row) => row.textContent)
}

async function openSettleUp() {
  // The page header's button; the balances section has another one.
  const [button] = await screen.findAllByRole('button', { name: 'Settle up' })
  fireEvent.click(button)
  return screen.findByRole('dialog', { name: 'Settle up' })
}

describe('balances and settling up', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    // A Monday: "Today" is 16 November.
    vi.useFakeTimers({
      now: Date.parse('2026-11-16T12:00:00Z'),
      toFake: ['Date'],
    })
  })

  afterEach(async () => {
    vi.useRealTimers()
    await act(() => i18n.changeLanguage('en'))
  })

  it("shows each member's balance, who is owed first, and what you owe to whom", async () => {
    const group = portoWeekend()
    mockApi({ groups: [group], groupExpenses: weekendExpenses(group.id) })
    renderApp(`/groups/${group.id}`)

    await waitFor(() =>
      expect(balanceRows()).toEqual([
        'RRitais owed+€60.00',
        'ATYouowe−€24.00',
        'JJoãoowes−€36.00',
      ]),
    )
    // Only the data has colour: what someone is owed is green, what they owe is not.
    const ritaAmount = screen.getByText('+€60.00')
    expect(ritaAmount).toHaveClass('text-income-text')
    expect(screen.getByText('−€36.00')).not.toHaveClass('text-income-text')

    expect(
      await screen.findByText('To Rita, in the suggested settle-up'),
    ).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'You owe' })).toBeInTheDocument()
    expect(screen.getByText('€24.00')).toBeInTheDocument()
  })

  it('records a suggested payment with one tap, once even when tapped twice', async () => {
    const group = portoWeekend()
    const api = mockApi({
      groups: [group],
      groupExpenses: weekendExpenses(group.id),
    })
    renderApp(`/groups/${group.id}`)

    const dialog = await openSettleUp()
    // Asked for again when the dialog opens, then kept as they came.
    expect(
      await within(dialog).findByText(
        'Porto weekend · 2 payments clear every balance',
      ),
    ).toBeInTheDocument()
    const suggestions = within(dialog)
      .getAllByRole('listitem')
      .map((row) => row.textContent)
    expect(suggestions).toEqual([
      // Each row starts with the two people's initials, drawn as avatars.
      "JRJoão pays RitaClears João's balance€36.00Record",
      'ATRYou pay RitaClears your balance€24.00Record',
    ])

    const record = within(dialog).getByRole('button', {
      name: 'Record: You pay Rita, €24.00',
    })
    fireEvent.click(record)
    fireEvent.click(record)

    expect(await within(dialog).findByText('Recorded')).toBeInTheDocument()
    const calls = sent(api.post, '/v1/groups/{id}/settlements')
    expect(calls.length).toBeGreaterThanOrEqual(1)
    // Every attempt carries the same key, so the API records the payment once.
    expect(
      new Set(calls.map(([, init]) => init.params.header['Idempotency-Key']))
        .size,
    ).toBe(1)
    expect(calls[0][1].body).toEqual({
      from: me.userId,
      to: rita.userId,
      amount: { amountMinor: 2400, currency: 'EUR' },
      occurredOn: '2026-11-16',
    })
    expect(api.groupExpenses.settlements).toHaveLength(1)
    // The other suggestion stays as it was, still to record.
    expect(
      within(dialog).getByRole('button', {
        name: 'Record: João pays Rita, €36.00',
      }),
    ).toBeEnabled()

    fireEvent.click(within(dialog).getByRole('button', { name: 'Done' }))
    await waitFor(() =>
      expect(balanceRows()).toEqual([
        'RRitais owed+€36.00',
        'ATYousettled up€0.00',
        'JJoãoowes−€36.00',
      ]),
    )
    expect(
      screen.getByText('You owe nobody anything, and nobody owes you.'),
    ).toBeInTheDocument()
  })

  it('records another payment between any two members, each with its own key', async () => {
    const group = portoWeekend()
    const api = mockApi({
      groups: [group],
      groupExpenses: weekendExpenses(group.id),
    })
    renderApp(`/groups/${group.id}`)
    const dialog = await openSettleUp()

    // The same person twice is refused before anything is sent.
    fireEvent.change(within(dialog).getByLabelText('To'), {
      target: { value: me.userId },
    })
    fireEvent.change(within(dialog).getByLabelText('Amount'), {
      target: { value: '10' },
    })
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Record payment' }),
    )
    expect(
      within(dialog).getByText('Pick two different people.'),
    ).toBeInTheDocument()

    fireEvent.change(within(dialog).getByLabelText('From'), {
      target: { value: joao.userId },
    })
    fireEvent.change(within(dialog).getByLabelText('Amount'), {
      target: { value: '10,50' },
    })
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Record payment' }),
    )
    expect(
      await within(dialog).findByText('Payment recorded.'),
    ).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Amount')).toHaveValue('')

    fireEvent.change(within(dialog).getByLabelText('From'), {
      target: { value: rita.userId },
    })
    fireEvent.change(within(dialog).getByLabelText('Amount'), {
      target: { value: '5' },
    })
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Record payment' }),
    )
    await waitFor(() => expect(api.groupExpenses.settlements).toHaveLength(2))

    const calls = sent(api.post, '/v1/groups/{id}/settlements')
    expect(calls.map(([, init]) => init.body)).toEqual([
      {
        from: joao.userId,
        to: me.userId,
        amount: { amountMinor: 1050, currency: 'EUR' },
        occurredOn: '2026-11-16',
      },
      {
        from: rita.userId,
        to: me.userId,
        amount: { amountMinor: 500, currency: 'EUR' },
        occurredOn: '2026-11-16',
      },
    ])
    expect(calls[0][1].params.header['Idempotency-Key']).not.toBe(
      calls[1][1].params.header['Idempotency-Key'],
    )
  })

  it('records a corrected payment with the same key after a failed attempt', async () => {
    const group = portoWeekend()
    const api = mockApi({
      groups: [group],
      groupExpenses: weekendExpenses(group.id),
    })
    // The first attempt never reaches the API, which stores nothing for it.
    api.post.mockImplementationOnce((() =>
      Promise.reject(networkError)) as never)
    renderApp(`/groups/${group.id}`)
    const dialog = await openSettleUp()

    fireEvent.change(within(dialog).getByLabelText('Amount'), {
      target: { value: '20' },
    })
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Record payment' }),
    )
    expect(
      await within(dialog).findByText(
        'Could not reach MoneyTrail. Check your connection and try again.',
      ),
    ).toBeInTheDocument()

    fireEvent.change(within(dialog).getByLabelText('Amount'), {
      target: { value: '25' },
    })
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Record payment' }),
    )
    expect(
      await within(dialog).findByText('Payment recorded.'),
    ).toBeInTheDocument()

    const keys = sent(api.post, '/v1/groups/{id}/settlements').map(
      ([, init]) => init.params.header['Idempotency-Key'],
    )
    expect(keys).toHaveLength(2)
    expect(keys[1]).toBe(keys[0])
    expect(
      api.groupExpenses.settlements.map(({ amount }) => amount.amountMinor),
    ).toEqual([2500])
  })

  it('does not record a payment twice when its answer was lost and the amount changed', async () => {
    const group = portoWeekend()
    const api = mockApi({
      groups: [group],
      groupExpenses: weekendExpenses(group.id),
    })
    const answer = api.post.getMockImplementation()!
    // Recorded by the API, but the answer never arrives.
    api.post.mockImplementationOnce((async (
      ...args: Parameters<typeof answer>
    ) => {
      await answer(...args)
      throw networkError
    }) as never)
    renderApp(`/groups/${group.id}`)
    const dialog = await openSettleUp()

    fireEvent.change(within(dialog).getByLabelText('Amount'), {
      target: { value: '20' },
    })
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Record payment' }),
    )
    await within(dialog).findByText(
      'Could not reach MoneyTrail. Check your connection and try again.',
    )

    // A new key here would record a second payment.
    fireEvent.change(within(dialog).getByLabelText('Amount'), {
      target: { value: '21' },
    })
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Record payment' }),
    )
    expect(
      await within(dialog).findByText(
        'An earlier attempt was already saved. Close this window and check the list.',
      ),
    ).toBeInTheDocument()

    const keys = sent(api.post, '/v1/groups/{id}/settlements').map(
      ([, init]) => init.params.header['Idempotency-Key'],
    )
    expect(keys[1]).toBe(keys[0])
    expect(
      api.groupExpenses.settlements.map(({ amount }) => amount.amountMinor),
    ).toEqual([2000])
    // The group is asked for again, so the payment that was recorded shows once the dialog closes.
    fireEvent.click(within(dialog).getByRole('button', { name: 'Done' }))
    expect(
      await screen.findByRole('link', { name: /You paid Rita/ }),
    ).toBeInTheDocument()
  })

  it('says when an amount is missing', async () => {
    const group = portoWeekend()
    const api = mockApi({ groups: [group] })
    renderApp(`/groups/${group.id}`)
    const dialog = await openSettleUp()

    expect(
      await within(dialog).findByText(
        'Everyone is settled up: there is nothing to pay.',
      ),
    ).toBeInTheDocument()
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Record payment' }),
    )
    expect(within(dialog).getByText('Enter an amount.')).toBeInTheDocument()
    expect(sent(api.post, '/v1/groups/{id}/settlements')).toHaveLength(0)
  })

  it('offers no settling up in an archived group', async () => {
    const group = portoWeekend({ archived: true })
    mockApi({ groups: [group], groupExpenses: weekendExpenses(group.id) })
    renderApp(`/groups/${group.id}`)

    await waitFor(() => expect(balanceRows()).toHaveLength(3))
    expect(screen.queryByRole('button', { name: 'Settle up' })).toBeNull()
  })

  it('opens a payment with what it changed and its trail, and reverses it', async () => {
    const group = portoWeekend()
    const payment = testSettlement(group.id, {
      from: joao.userId,
      to: rita.userId,
      amount: 2000,
      occurredOn: '2026-11-16',
    })
    const api = mockApi({ groups: [group], settlements: [payment] })
    renderApp(`/groups/${group.id}`)

    fireEvent.click(await screen.findByRole('link', { name: /João paid Rita/ }))
    const sheet = await screen.findByRole('dialog', { name: 'João paid Rita' })
    expect(
      within(sheet).getByText('Payment · Porto weekend'),
    ).toBeInTheDocument()
    expect(
      within(sheet).getByText(/^Monday,? 16 November 2026 · recorded by João$/),
    ).toBeInTheDocument()
    const effect = within(
      within(sheet).getByRole('region', { name: 'What it changed' }),
    )
    expect(effect.getByText("João's balance").nextSibling).toHaveTextContent(
      '+€20.00',
    )
    expect(effect.getByText("Rita's balance").nextSibling).toHaveTextContent(
      '−€20.00',
    )
    expect(effect.getByText('Your balance').nextSibling).toHaveTextContent(
      'No change',
    )

    fireEvent.click(
      within(sheet).getByRole('button', { name: 'Reverse payment' }),
    )
    const confirm = within(sheet).getByRole('group', {
      name: 'Reverse this payment?',
    })
    fireEvent.click(within(confirm).getByRole('button', { name: 'Reverse' }))

    expect(
      await within(sheet).findByText(
        "Reversed. It no longer counts towards anyone's balance.",
      ),
    ).toBeInTheDocument()
    expect(within(sheet).getByText('Original reversed')).toBeInTheDocument()
    const [call] = sent(
      api.post,
      '/v1/groups/{id}/settlements/{settlementId}/reverse',
    )
    expect(call[1].params.header['Idempotency-Key']).toBeTruthy()
    expect(api.groupExpenses.settlements[0].reversed).toBe(true)
    // Gone from the group's list, which counts only what is in effect.
    await waitFor(() =>
      expect(screen.queryByRole('link', { name: /João paid Rita/ })).toBeNull(),
    )
  })
})

describe('activity', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.useFakeTimers({
      now: Date.parse('2026-11-16T12:00:00Z'),
      toFake: ['Date'],
    })
  })

  afterEach(async () => {
    vi.useRealTimers()
    await act(() => i18n.changeLanguage('en'))
  })

  function weekendActivity(groupId: string) {
    return [
      testActivity(groupId, {
        kind: 'GROUP_CREATED',
        actorId: rita.userId,
        groupName: 'Porto weekend',
        occurredAt: '2026-11-13T18:00:00Z',
      }),
      testActivity(groupId, {
        kind: 'MEMBER_LEFT',
        actorId: pedro.userId,
        occurredAt: '2026-11-14T09:00:00Z',
      }),
      testActivity(groupId, {
        kind: 'EXPENSE_ADDED',
        actorId: rita.userId,
        description: 'Francesinhas dinner',
        amount: { amountMinor: 8400, currency: 'EUR' },
        occurredAt: '2026-11-14T22:15:00Z',
      }),
      testActivity(groupId, {
        kind: 'EXPENSE_EDITED',
        actorId: me.userId,
        description: 'Airbnb, 2 nights',
        amount: { amountMinor: 24000, currency: 'EUR' },
        previousAmount: { amountMinor: 20000, currency: 'EUR' },
        occurredAt: '2026-11-15T10:02:00Z',
      }),
      testActivity(groupId, {
        kind: 'SETTLEMENT_RECORDED',
        actorId: joao.userId,
        from: joao.userId,
        to: me.userId,
        amount: { amountMinor: 2000, currency: 'EUR' },
        occurredAt: '2026-11-16T11:00:00Z',
      }),
      testActivity(groupId, {
        kind: 'SETTLEMENT_RECORDED',
        actorId: me.userId,
        from: joao.userId,
        to: rita.userId,
        amount: { amountMinor: 500, currency: 'EUR' },
        occurredAt: '2026-11-16T11:30:00Z',
      }),
    ]
  }

  function activityLines(title = 'Activity') {
    return (
      within(screen.getByRole('region', { name: title }))
        .getAllByRole('listitem')
        .map((item) => item.firstElementChild?.nextElementSibling?.firstChild)
        // Portuguese amounts have a no-break space before "€": compared as ordinary spaces.
        .map((line) => line?.textContent?.replace(/\s/g, ' '))
    )
  }

  it('shows what happened, newest first, naming you and people who left', async () => {
    const group = portoWeekend()
    mockApi({
      groups: [group],
      activity: weekendActivity(group.id),
      formerMembers: [pedro],
    })
    renderApp(`/groups/${group.id}`)

    await waitFor(() =>
      expect(activityLines()).toEqual([
        'You recorded a payment of €5.00 from João to Rita',
        'João recorded a payment of €20.00 to you',
        'You edited Airbnb, 2 nights: €200.00 → €240.00',
        'Rita added Francesinhas dinner, €84.00',
        'Pedro left the group',
        'Rita created the group',
      ]),
    )
    const region = within(screen.getByRole('region', { name: 'Activity' }))
    expect(region.getAllByText(/^Today, \d\d:\d\d$/)).toHaveLength(2)
    expect(region.getByText(/^Yesterday, \d\d:\d\d$/)).toBeInTheDocument()
    expect(region.getAllByText(/^Saturday, \d\d:\d\d$/)).toHaveLength(2)
  })

  it('writes each line for you in Portuguese, with the verb that goes with it', async () => {
    await act(() => i18n.changeLanguage('pt-PT'))
    const group = portoWeekend()
    mockApi({ groups: [group], activity: weekendActivity(group.id) })
    renderApp(`/groups/${group.id}`)

    await waitFor(() =>
      expect(activityLines('Atividade').slice(0, 4)).toEqual([
        'Tu registaste um pagamento de 5,00 € de João para Rita',
        'João registou um pagamento de 20,00 € para ti',
        'Tu editaste Airbnb, 2 nights: 200,00 € → 240,00 €',
        'Rita adicionou Francesinhas dinner, 84,00 €',
      ]),
    )
  })

  it('loads more, and asks again when the app comes back into view', async () => {
    const group = portoWeekend()
    const many = Array.from({ length: 51 }, (_, index) =>
      testActivity(group.id, {
        kind: 'EXPENSE_ADDED',
        actorId: rita.userId,
        description: `Expense ${index + 1}`,
        amount: { amountMinor: 100, currency: 'EUR' },
        occurredAt: new Date(
          Date.parse('2026-11-01T00:00:00Z') + index * 60_000,
        ).toISOString(),
      }),
    )
    const api = mockApi({ groups: [group], activity: many })
    renderApp(`/groups/${group.id}`)

    await waitFor(() => expect(activityLines()).toHaveLength(50))
    expect(activityLines().at(-1)).toBe('Rita added Expense 2, €1.00')
    const region = within(screen.getByRole('region', { name: 'Activity' }))
    fireEvent.click(region.getByRole('button', { name: 'Load more' }))
    await waitFor(() => expect(activityLines()).toHaveLength(51))
    expect(region.queryByRole('button', { name: 'Load more' })).toBeNull()

    // Something happened while the app was in the background.
    api.groupSettlements.activity.push(
      testActivity(group.id, {
        kind: 'MEMBER_JOINED',
        actorId: joao.userId,
        occurredAt: '2026-11-16T11:59:00Z',
      }),
    )
    act(() => {
      document.dispatchEvent(new Event('visibilitychange', { bubbles: true }))
    })
    await waitFor(() =>
      expect(activityLines()[0]).toBe('João joined the group'),
    )
  })
})
