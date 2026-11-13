import { act, fireEvent, screen, within, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '@/i18n/i18n'
import { asNewTransaction } from '@/lib/transactions'
import { euros, mockApi } from '@/test/mockApi'
import { OCTOBER_25, october } from '@/test/october'
import { renderApp } from '@/test/renderApp'

type Sent = [
  path: string,
  init: {
    params: { path?: { id: string }; header: Record<string, string> }
    body: unknown
  },
]

function sentReversals(post: ReturnType<typeof mockApi>['post']) {
  return (post.mock.calls as unknown as Sent[]).filter(
    ([path]) => path === '/v1/transactions/{id}/reverse',
  )
}

function rows(table: HTMLElement) {
  return within(table)
    .getAllByRole('row')
    .map((row) =>
      [...row.querySelectorAll('th, td')].map((cell) => cell.textContent),
    )
}

function trailSteps(sheet: HTMLElement) {
  return within(sheet)
    .getAllByRole('listitem')
    .map((step) => step.querySelector('p')?.textContent)
}

/**
 * The design canvas' October, with "Dinner out" recorded as €25.70 and then corrected to €27.50,
 * as in its transaction detail.
 */
function octoberWithAnEdit() {
  const data = october()
  Object.assign(data.named('Dinner out'), {
    amount: euros(2570),
    edited: false,
  })
  const api = mockApi(data)
  const original = data.named('Dinner out')
  const correction = api.ledger.reverse(original.id, {
    ...asNewTransaction(original),
    amount: euros(2750),
  })
  return { ...data, ...api, original, correction }
}

describe('TransactionDetailSheet', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.useFakeTimers({ now: OCTOBER_25, toFake: ['Date'] })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  /** Opens the detail of `description` from the list, as a person would. */
  async function open(description: string, path = '/transactions') {
    renderApp(path)
    await screen.findByText(/transactions? in October$/)
    fireEvent.click(
      screen.getByRole('link', { name: new RegExp(`^${description}`) }),
    )
    return screen.findByRole('dialog', { name: description })
  }

  it('shows the details, the balanced ledger entry and the trail of an edited transaction', async () => {
    const { correction, original } = octoberWithAnEdit()
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText },
      configurable: true,
    })
    const sheet = await open('Dinner out')

    expect(sheet).toHaveTextContent('Dinner out−€27.50')
    expect(sheet).toHaveTextContent('Thursday 22 October 2026 · Credit card')
    expect(sheet).toHaveTextContent('EditedCorrected from €25.70')

    const details = within(sheet).getByRole('region', { name: 'Details' })
    expect(details).toHaveTextContent(
      'CategoryFoodAccountCredit cardDateThursday 22 October 2026Recorded25 October 2026 at',
    )

    const ledger = within(sheet).getByRole('region', { name: 'Ledger entry' })
    expect(ledger).toHaveTextContent('Balanced')
    expect(rows(within(ledger).getByRole('table'))).toEqual([
      ['Account', 'Amount (EUR)'],
      ['Expense:Food', '+27.50'],
      ['Liability:Credit card', '−27.50'],
      ['Total', '0.00'],
    ])
    expect(ledger).toHaveTextContent(`Entry ${correction.transaction.id}`)
    fireEvent.click(
      within(ledger).getByRole('button', { name: 'Copy entry ID' }),
    )
    expect(writeText).toHaveBeenCalledWith(correction.transaction.id)
    expect(await within(ledger).findByRole('status')).toHaveTextContent(
      'Entry ID copied',
    )

    const trail = within(sheet).getByRole('region', { name: 'Trail' })
    expect(trailSteps(trail)).toEqual([
      'Corrected to €27.50 · current',
      'Original reversed',
      'Recorded as €25.70',
    ])
    const [, reversal] = correction.trail
    expect(trail).toHaveTextContent(
      `entry ${reversal.entryId.slice(0, 8)}…${reversal.entryId.slice(-4)} cancels ${original.id.slice(0, 8)}…${original.id.slice(-4)}`,
    )
  })

  it('edits a transaction as a reversal plus its correction, in one request', async () => {
    const data = october()
    const { post, transactions } = mockApi(data)
    const groceries = data.named('Groceries')
    const sheet = await open('Groceries')

    fireEvent.click(within(sheet).getByRole('button', { name: 'Edit' }))
    const dialog = await screen.findByRole('dialog', {
      name: 'Edit transaction',
    })
    expect(within(dialog).getByLabelText('Amount')).toHaveValue('38.72')
    expect(within(dialog).getByLabelText('Description')).toHaveValue(
      'Groceries',
    )
    expect(within(dialog).getByRole('radio', { name: 'Food' })).toBeChecked()
    expect(within(dialog).getByLabelText('Paid from')).toHaveValue(
      data.checking.id,
    )
    expect(within(dialog).getByLabelText('Date')).toHaveValue('2026-10-24')
    expect(dialog).toHaveTextContent(
      'Saving reverses the original and records the corrected one.',
    )

    fireEvent.change(within(dialog).getByLabelText('Amount'), {
      target: { value: '40,12' },
    })
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Save changes' }),
    )

    const corrected = await screen.findByRole('dialog', { name: 'Groceries' })
    await within(corrected).findByText('Corrected from €38.72')
    expect(sentReversals(post)).toEqual([
      [
        '/v1/transactions/{id}/reverse',
        {
          params: {
            path: { id: groceries.id },
            header: { 'Idempotency-Key': expect.any(String) },
          },
          body: {
            replacement: {
              ...asNewTransaction(groceries),
              amount: { amountMinor: 4012, currency: 'EUR' },
            },
          },
        },
      ],
    ])
    expect(post).toHaveBeenCalledTimes(1)
    expect(corrected).toHaveTextContent('Groceries−€40.12')
    expect(
      trailSteps(within(corrected).getByRole('region', { name: 'Trail' })),
    ).toEqual([
      'Corrected to €40.12 · current',
      'Original reversed',
      'Recorded as €38.72',
    ])
    expect(transactions.filter((t) => t.description === 'Groceries')).toEqual([
      expect.objectContaining({ reversed: true, edited: false }),
      expect.objectContaining({ reversed: false, edited: true }),
    ])

    // Back on the list, the correction has taken the original's place.
    fireEvent.click(within(corrected).getByRole('button', { name: 'Close' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    const yesterday = screen.getByRole('region', {
      name: 'Yesterday, Saturday, 24 October',
    })
    expect(within(yesterday).getAllByRole('link')[0]).toHaveTextContent(
      'GroceriesEditedFood·Everyday checking−€40.12',
    )
    expect(yesterday).toHaveTextContent('Net for the day: −€55.12')
  })

  it('saves nothing when an edit changed nothing', async () => {
    const { post } = mockApi(october())
    const sheet = await open('Groceries')

    fireEvent.click(within(sheet).getByRole('button', { name: 'Edit' }))
    const dialog = await screen.findByRole('dialog', {
      name: 'Edit transaction',
    })
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Save changes' }),
    )

    await waitFor(() =>
      expect(
        screen.queryByRole('dialog', { name: 'Edit transaction' }),
      ).toBeNull(),
    )
    expect(post).not.toHaveBeenCalled()
  })

  it('asks before reversing, and reverses once however many taps', async () => {
    const data = october()
    const { post, transactions } = mockApi(data)
    const sheet = await open('Groceries')

    const reverse = within(sheet).getByRole('button', { name: 'Reverse' })
    fireEvent.click(reverse)
    expect(reverse).toHaveAttribute('aria-expanded', 'true')
    const confirm = within(sheet).getByRole('group', {
      name: 'Reverse this transaction?',
    })
    expect(confirm).toHaveTextContent(
      'We add an entry that cancels it exactly, so the balance goes back to what it was. Both entries stay in your history.',
    )
    fireEvent.click(within(confirm).getByRole('button', { name: 'Keep it' }))
    expect(
      within(sheet).queryByRole('group', { name: 'Reverse this transaction?' }),
    ).not.toBeInTheDocument()
    expect(post).not.toHaveBeenCalled()

    fireEvent.click(reverse)
    const confirmAgain = within(sheet).getByRole('group', {
      name: 'Reverse this transaction?',
    })
    const yes = within(confirmAgain).getByRole('button', {
      name: 'Reverse transaction',
    })
    fireEvent.click(yes)
    fireEvent.click(yes)

    expect(
      await within(sheet).findByText(
        "Reversed. It no longer counts towards your balances or the month's totals.",
      ),
    ).toBeVisible()
    const keys = sentReversals(post).map(
      ([, init]) => init.params.header['Idempotency-Key'],
    )
    expect(new Set(keys).size).toBe(1)
    expect(sentReversals(post)[0][1].body).toEqual({})
    expect(transactions.filter((t) => t.description === 'Groceries')).toEqual([
      expect.objectContaining({ reversed: true }),
    ])
    expect(
      within(sheet).queryByRole('button', { name: 'Edit' }),
    ).not.toBeInTheDocument()
    expect(
      trailSteps(within(sheet).getByRole('region', { name: 'Trail' })),
    ).toEqual(['Original reversed', 'Recorded as €38.72'])

    fireEvent.click(within(sheet).getByRole('button', { name: 'Close' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(screen.queryByText('Groceries')).not.toBeInTheDocument()
    expect(
      screen.getByRole('region', { name: 'Yesterday, Saturday, 24 October' }),
    ).toHaveTextContent('Net for the day: −€15.00')
  })

  it('shows a transfer from one account to another', async () => {
    const data = october()
    mockApi(data)
    const sheet = await open('Emergency fund top-up')

    expect(sheet).toHaveTextContent('Emergency fund top-up€200.00')
    expect(
      within(sheet).getByRole('region', { name: 'Details' }),
    ).toHaveTextContent('FromEveryday checkingToEmergency fundDate')
    expect(rows(within(sheet).getByRole('table')).slice(1)).toEqual([
      ['Asset:Emergency fund', '+200.00'],
      ['Asset:Everyday checking', '−200.00'],
      ['Total', '0.00'],
    ])
  })

  it('links an earlier version to the current one', async () => {
    const { original, correction } = octoberWithAnEdit()
    renderApp(`/transactions/${original.id}`)

    const sheet = await screen.findByRole('dialog', { name: 'Dinner out' })
    expect(
      within(sheet).getByText(
        'This is an earlier version. An edit replaced it, so it no longer counts.',
      ),
    ).toBeVisible()
    expect(
      within(sheet).queryByRole('button', { name: 'Reverse' }),
    ).not.toBeInTheDocument()
    fireEvent.click(
      within(sheet).getByRole('link', { name: 'See the current version' }),
    )
    expect(
      await within(sheet).findByText('Corrected from €25.70'),
    ).toBeVisible()
    expect(sheet).toHaveTextContent(`Entry ${correction.transaction.id}`)
  })

  it('says when a transaction does not exist', async () => {
    mockApi(october())
    renderApp('/transactions/00000000-0000-4000-8000-999999999999')

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Could not load this transaction. This no longer exists. Reload the page.',
    )
  })

  it('closes back to the list with its filters', async () => {
    mockApi(october())
    const sheet = await open('Brunch', '/transactions?type=EXPENSE')

    fireEvent.click(within(sheet).getByRole('button', { name: 'Close' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(screen.getByRole('button', { name: 'Expenses' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    expect(screen.getByText('6 transactions in October')).toBeInTheDocument()
  })

  it('is in Portuguese too', async () => {
    octoberWithAnEdit()
    const sheet = await open('Dinner out')
    await act(() => i18n.changeLanguage('pt-PT'))

    expect(sheet).toHaveTextContent('EditadoCorrigido de 25,70')
    expect(rows(within(sheet).getByRole('table'))).toEqual([
      ['Conta', 'Valor (EUR)'],
      ['Despesa:Alimentação', '+27,50'],
      ['Passivo:Credit card', '−27,50'],
      ['Total', '0,00'],
    ])
    expect(
      within(sheet).getByRole('region', { name: 'Histórico' }),
    ).toHaveTextContent('Corrigido para 27,50')
    expect(
      within(sheet).getByRole('button', { name: 'Estornar' }),
    ).toBeInTheDocument()
  })
})
