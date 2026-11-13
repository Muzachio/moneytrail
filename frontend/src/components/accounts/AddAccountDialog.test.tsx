import { fireEvent, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockApi, testAccount } from '@/test/mockApi'
import { renderApp } from '@/test/renderApp'

const networkError = new TypeError('Failed to fetch')

async function openAddAccount() {
  renderApp('/accounts')
  await screen.findByText('Everyday checking')
  fireEvent.click(screen.getByRole('button', { name: 'Add account' }))
  return screen.findByRole('dialog', { name: 'Add account' })
}

function fill(dialog: HTMLElement, label: string | RegExp, value: string) {
  fireEvent.change(within(dialog).getByLabelText(label), {
    target: { value },
  })
}

function submit(dialog: HTMLElement) {
  fireEvent.click(within(dialog).getByRole('button', { name: 'Add account' }))
}

type SentAccount = [
  path: string,
  init: { params: { header: Record<string, string> }; body: unknown },
]

/** The calls of `POST /v1/accounts`, typed: the spy's own types cannot see openapi-fetch's overloads. */
function sentAccounts(post: ReturnType<typeof mockApi>['post']) {
  return post.mock.calls as unknown as SentAccount[]
}

function sentKeys(post: ReturnType<typeof mockApi>['post']) {
  return sentAccounts(post).map(
    ([, init]) => init.params.header['Idempotency-Key'],
  )
}

describe('AddAccountDialog', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  function mockAccounts() {
    return mockApi({ accounts: [testAccount({ name: 'Everyday checking' })] })
  }

  it('adds an account with its balance today, with an Idempotency-Key', async () => {
    const { post } = mockAccounts()
    const dialog = await openAddAccount()

    fill(dialog, 'Name', ' Trip savings ')
    fireEvent.click(within(dialog).getByRole('radio', { name: 'Savings' }))
    fill(dialog, 'Balance today', '1200,50')
    submit(dialog)

    expect(await screen.findByText('Trip savings')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(post).toHaveBeenCalledExactlyOnceWith('/v1/accounts', {
      params: { header: { 'Idempotency-Key': expect.any(String) } },
      body: {
        name: 'Trip savings',
        type: 'SAVINGS',
        currency: 'EUR',
        openingBalance: { amountMinor: 120050, currency: 'EUR' },
      },
    })
    expect(sentKeys(post)[0]).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4/)
  })

  it('sends what a credit card owes as a negative balance', async () => {
    const { post } = mockAccounts()
    const dialog = await openAddAccount()

    fill(dialog, 'Name', 'Credit card')
    fireEvent.click(within(dialog).getByRole('radio', { name: 'Credit card' }))
    expect(dialog).toHaveTextContent(
      'Saved as money you owe, so your net worth stays right.',
    )
    fill(dialog, 'Amount owed today', '312.40')
    submit(dialog)

    await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(post.mock.calls[0][1]).toMatchObject({
      body: {
        type: 'CREDIT_CARD',
        openingBalance: { amountMinor: -31240, currency: 'EUR' },
      },
    })
    expect(
      screen.getByRole('region', { name: 'Credit cards' }),
    ).toHaveTextContent('−€312.40')
  })

  it('records no opening entry for an account that starts empty', async () => {
    const { post } = mockAccounts()
    const dialog = await openAddAccount()

    fill(dialog, 'Name', 'Wallet')
    fireEvent.click(within(dialog).getByRole('radio', { name: 'Cash' }))
    fill(dialog, 'Balance today', '0,00')
    submit(dialog)

    await vi.waitFor(() => expect(post).toHaveBeenCalled())
    expect(post.mock.calls[0][1]).toMatchObject({
      body: { name: 'Wallet', type: 'CASH', currency: 'EUR' },
    })
    expect(post.mock.calls[0][1]).not.toHaveProperty('body.openingBalance')
  })

  it('offers euros only, for now', async () => {
    mockAccounts()
    const dialog = await openAddAccount()

    const currency = within(dialog).getByLabelText('Currency')
    expect(currency).toHaveValue('EUR · Euro')
    expect(currency).toBeDisabled()
    expect(
      within(dialog).getByRole('radio', { name: 'Checking' }),
    ).toBeChecked()
  })

  it('checks the name and the amount before sending anything', async () => {
    const { post } = mockAccounts()
    const dialog = await openAddAccount()

    fill(dialog, 'Name', 'x'.repeat(51))
    fill(dialog, 'Balance today', '12.505')
    submit(dialog)

    const name = within(dialog).getByLabelText('Name')
    expect(
      await within(dialog).findByText('Use at most 50 characters.'),
    ).toBeVisible()
    expect(name).toHaveAttribute('aria-invalid', 'true')
    const balance = within(dialog).getByLabelText('Balance today')
    expect(balance).toHaveAttribute('aria-invalid', 'true')
    expect(balance).toHaveAccessibleDescription(
      /Use at most 2 decimal places\./,
    )

    fill(dialog, 'Name', '')
    fill(dialog, 'Balance today', '12,5,0')
    submit(dialog)
    expect(await within(dialog).findByText('Enter a name.')).toBeVisible()
    expect(
      within(dialog).getByText('Enter an amount in numbers, such as 1250.50.'),
    ).toBeVisible()
    expect(post).not.toHaveBeenCalled()
  })

  it("shows the API's answer in the form", async () => {
    const { post } = mockAccounts()
    post.mockResolvedValueOnce({
      error: { title: 'Bad Request', status: 400, code: 'bad_request' },
      response: new Response(null, { status: 400 }),
    } as never)
    const dialog = await openAddAccount()

    fill(dialog, 'Name', 'Wallet')
    submit(dialog)

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'MoneyTrail could not accept these details. Check them and try again.',
    )
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('sends the same key again after a network error, so the account is created once', async () => {
    const { post } = mockAccounts()
    post.mockRejectedValueOnce(networkError)
    const dialog = await openAddAccount()

    fill(dialog, 'Name', 'Wallet')
    fill(dialog, 'Balance today', '45,20')
    submit(dialog)
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'Could not reach MoneyTrail. Check your connection and try again.',
    )
    submit(dialog)

    expect(await screen.findByText('Wallet')).toBeInTheDocument()
    const [first, retried] = sentKeys(post)
    expect(retried).toBe(first)
  })

  it('starts a new action, with a new key, each time the dialog opens', async () => {
    const { post } = mockAccounts()
    let dialog = await openAddAccount()
    fill(dialog, 'Name', 'Wallet')
    submit(dialog)
    await screen.findByText('Wallet')

    fireEvent.click(screen.getByRole('button', { name: 'Add account' }))
    dialog = await screen.findByRole('dialog', { name: 'Add account' })
    expect(within(dialog).getByLabelText('Name')).toHaveValue('')
    fill(dialog, 'Name', 'Wallet')
    submit(dialog)
    await vi.waitFor(() => expect(post).toHaveBeenCalledTimes(2))

    const [first, second] = sentKeys(post)
    expect(second).not.toBe(first)
  })
})
