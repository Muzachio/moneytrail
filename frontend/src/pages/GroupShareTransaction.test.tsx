import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  defaultCategories,
  euros,
  mockApi,
  testAccount,
  testTransaction,
} from '@/test/mockApi'
import { renderApp } from '@/test/renderApp'

/**
 * A share of a group recorded by its mirror (#94): it shows where it comes from, and only its
 * account changes here; the rest changes in the group.
 */
describe('a share of a group in the transactions', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  const groupId = crypto.randomUUID()
  const today = new Date().toISOString().slice(0, 10)

  function aDinnerAnaPaid() {
    const checking = testAccount({ name: 'Everyday checking' })
    const savings = testAccount({ name: 'Savings', type: 'SAVINGS' })
    const groupAccount = testAccount({
      name: 'Porto weekend',
      type: 'GROUP',
      groupId,
      balance: euros(2000),
    })
    const categories = defaultCategories()
    const food = categories.find((category) => category.key === 'food')!
    const dinner = testTransaction({
      description: 'Dinner',
      amount: euros(1000),
      occurredOn: today,
      categoryId: food.id,
      accountId: checking.id,
      group: {
        groupId,
        groupAccountId: groupAccount.id,
        groupEntryId: crypto.randomUUID(),
        type: 'EXPENSE',
        paid: euros(3000),
      },
    })
    const api = mockApi({
      accounts: [checking, savings, groupAccount],
      categories,
      transactions: [dinner],
    })
    return { api, dinner, savings }
  }

  it('says it comes from the group, and has neither Edit nor Reverse', async () => {
    const { dinner } = aDinnerAnaPaid()
    renderApp(`/transactions/${dinner.id}`)

    const sheet = await screen.findByRole('dialog', { name: 'Dinner' })

    expect(
      within(sheet).getByText(
        'From the group Porto weekend: change it in the group.',
      ),
    ).toBeInTheDocument()
    expect(
      within(sheet).getByRole('link', { name: 'Open the group' }),
    ).toHaveAttribute('href', `/groups/${groupId}`)
    expect(within(sheet).queryByRole('button', { name: 'Edit' })).toBeNull()
    expect(
      within(sheet).queryByRole('button', { name: 'Reverse transaction' }),
    ).toBeNull()
  })

  it('changes the account the money came from', async () => {
    const {
      api: { post },
      dinner,
      savings,
    } = aDinnerAnaPaid()
    renderApp(`/transactions/${dinner.id}`)

    const sheet = await screen.findByRole('dialog', { name: 'Dinner' })
    fireEvent.click(
      within(sheet).getByRole('button', { name: 'Change the account' }),
    )
    fireEvent.change(within(sheet).getByLabelText('Paid from'), {
      target: { value: savings.id },
    })
    fireEvent.click(within(sheet).getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith(
        '/v1/transactions/{id}/account',
        expect.objectContaining({
          params: {
            path: { id: dinner.id },
            header: { 'Idempotency-Key': expect.any(String) as unknown },
          },
          body: { accountId: savings.id },
        }),
      ),
    )
  })

  it('carries a Group badge in the list', async () => {
    aDinnerAnaPaid()
    renderApp('/transactions')

    const row = (await screen.findByText('Dinner')).closest('li')!

    expect(within(row).getByText('Group')).toBeInTheDocument()
  })
})
