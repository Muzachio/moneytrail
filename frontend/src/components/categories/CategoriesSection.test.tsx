import { act, fireEvent, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Category } from '@/api/categories'
import { i18n } from '@/i18n/i18n'
import {
  defaultCategories,
  mockApi,
  testAccount,
  testCategory,
} from '@/test/mockApi'
import { renderApp } from '@/test/renderApp'

/** The defaults, with Food renamed by the person and a category of their own. */
function someCategories(): Category[] {
  const categories = defaultCategories().map((category) =>
    category.key === 'food' ? { ...category, name: 'Groceries' } : category,
  )
  categories.splice(8, 0, testCategory('EXPENSE', { name: 'Pets' }))
  return categories
}

function list(name: string) {
  return screen.getByRole('region', { name })
}

function row(name: string) {
  return screen.getByText(name, { selector: 'li *' }).closest('li')!
}

async function renderCategories(categories = someCategories()) {
  const api = mockApi({
    accounts: [testAccount({ name: 'Everyday checking' })],
    categories,
  })
  renderApp('/accounts')
  await screen.findByRole('region', { name: 'Expenses' })
  return api
}

async function chooseFromMenu(menuLabel: string, item: RegExp) {
  fireEvent.keyDown(screen.getByRole('button', { name: menuLabel }), {
    key: 'Enter',
  })
  fireEvent.click(await screen.findByRole('menuitem', { name: item }))
}

describe('CategoriesSection', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('names default categories in the current language, and renamed ones as the person did', async () => {
    await renderCategories()

    const expenses = within(list('Expenses'))
      .getAllByRole('listitem')
      .map((item) => item.textContent)
    expect(expenses).toEqual([
      'Housing',
      'Groceries',
      'Transport',
      'Bills',
      'Shopping',
      'Leisure',
      'Health',
      'Other',
      'Pets',
    ])
    const income = within(list('Income'))
      .getAllByRole('listitem')
      .map((item) => item.textContent)
    expect(income).toEqual(['Salary', 'Refunds', 'Gifts', 'Other income'])

    await act(() => i18n.changeLanguage('pt-PT'))

    const despesas = within(list('Despesas'))
      .getAllByRole('listitem')
      .map((item) => item.textContent)
    expect(despesas).toEqual([
      'Casa',
      'Groceries',
      'Transportes',
      'Faturas',
      'Compras',
      'Lazer',
      'Saúde',
      'Outros',
      'Pets',
    ])
    expect(
      within(list('Receitas'))
        .getAllByRole('listitem')
        .map((item) => item.textContent),
    ).toEqual(['Salário', 'Reembolsos', 'Presentes', 'Outras receitas'])
  })

  it("shows each default category with its key's icon from the design", async () => {
    await renderCategories()

    const icon = (name: string) =>
      row(name).querySelector('svg')?.getAttribute('class')
    expect(icon('Housing')).toContain('lucide-house')
    // Renaming keeps the key, and so the icon.
    expect(icon('Groceries')).toContain('lucide-utensils')
    expect(icon('Transport')).toContain('lucide-tram-front')
    expect(icon('Bills')).toContain('lucide-receipt')
    expect(icon('Shopping')).toContain('lucide-shopping-bag')
    expect(icon('Leisure')).toContain('lucide-ticket')
    expect(icon('Health')).toContain('lucide-heart')
    expect(icon('Other')).toContain('lucide-tag')
    expect(icon('Salary')).toContain('lucide-banknote')
    expect(icon('Refunds')).toContain('lucide-rotate-ccw')
    expect(icon('Gifts')).toContain('lucide-gift')
    expect(icon('Other income')).toContain('lucide-tag')
    expect(icon('Pets')).toContain('lucide-tag')
  })

  it('adds a category of the kind chosen', async () => {
    const { post } = await renderCategories()

    fireEvent.click(screen.getByRole('button', { name: 'New income category' }))
    const dialog = await screen.findByRole('dialog', {
      name: 'New income category',
    })
    fireEvent.change(within(dialog).getByLabelText('Name'), {
      target: { value: 'Freelance' },
    })
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Add category' }),
    )

    // Until the dialog closes, the page around it is hidden from assistive technology.
    await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(await within(list('Income')).findByText('Freelance')).toBeVisible()
    expect(
      screen.getByRole('button', { name: 'New income category' }),
    ).toHaveFocus()
    expect(post).toHaveBeenCalledExactlyOnceWith('/v1/categories', {
      body: { kind: 'INCOME', name: 'Freelance' },
    })
  })

  it('shows a name the API says is taken at the name field', async () => {
    await renderCategories()

    fireEvent.click(
      screen.getByRole('button', { name: 'New expense category' }),
    )
    const dialog = await screen.findByRole('dialog', {
      name: 'New expense category',
    })
    const name = within(dialog).getByLabelText('Name')
    fireEvent.change(name, { target: { value: 'groceries' } })
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Add category' }),
    )

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'You already have an expense category with this name.',
    )
    expect(name).toHaveAttribute('aria-invalid', 'true')
    expect(name).toHaveAccessibleDescription(
      'You already have an expense category with this name.',
    )
    expect(name).toHaveFocus()
  })

  it('renames a default category, which then keeps its new name in every language', async () => {
    const { patch } = await renderCategories()

    await chooseFromMenu('More actions for Transport', /Rename/)
    const dialog = await screen.findByRole('dialog', {
      name: 'Rename category',
    })
    const name = within(dialog).getByLabelText('Name')
    expect(name).toHaveValue('Transport')
    fireEvent.change(name, { target: { value: 'Commuting' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))

    await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(await within(list('Expenses')).findByText('Commuting')).toBeVisible()
    expect(patch).toHaveBeenCalledWith('/v1/categories/{id}', {
      params: { path: { id: expect.any(String) } },
      body: { name: 'Commuting' },
    })
    await act(() => i18n.changeLanguage('pt-PT'))
    expect(within(list('Despesas')).getByText('Commuting')).toBeVisible()
  })

  it('leaves a default category translated when it is saved unchanged', async () => {
    const { patch } = await renderCategories()

    await chooseFromMenu('More actions for Transport', /Rename/)
    const dialog = await screen.findByRole('dialog', {
      name: 'Rename category',
    })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))

    await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(patch).not.toHaveBeenCalled()
  })

  it('archives a category and restores it', async () => {
    const { patch } = await renderCategories()

    await chooseFromMenu('More actions for Pets', /Archive/)

    const archived = await screen.findByRole('button', {
      name: 'Archived categories (1)',
    })
    expect(within(list('Expenses')).queryByText('Pets')).toBeNull()
    expect(patch).toHaveBeenCalledWith('/v1/categories/{id}', {
      params: { path: { id: expect.any(String) } },
      body: { archived: true },
    })

    fireEvent.click(archived)
    expect(row('Pets')).toHaveTextContent('Expense category')
    fireEvent.click(screen.getByRole('button', { name: 'Restore Pets' }))

    expect(await within(list('Expenses')).findByText('Pets')).toBeVisible()
    expect(
      screen.queryByRole('button', { name: /Archived categories/ }),
    ).toBeNull()
  })

  it('says why a category cannot be restored when its name was taken meanwhile', async () => {
    await renderCategories([
      ...defaultCategories(),
      testCategory('EXPENSE', { name: 'Pets', archived: true }),
      testCategory('EXPENSE', { name: 'pets' }),
    ])

    fireEvent.click(
      screen.getByRole('button', { name: 'Archived categories (1)' }),
    )
    fireEvent.click(screen.getByRole('button', { name: 'Restore Pets' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Could not restore Pets. You already have an expense category with this name.',
    )
  })

  it('says when the categories could not be loaded, and tries again', async () => {
    const { get } = mockApi({
      accounts: [testAccount({ name: 'Everyday checking' })],
    })
    const answer = get.getMockImplementation()!
    get.mockImplementation(((path: string) =>
      path === '/v1/categories'
        ? Promise.reject(new TypeError('Failed to fetch'))
        : (answer as unknown as (path: string) => unknown)(path)) as never)

    renderApp('/accounts')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Could not load your categories.')
    get.mockImplementation(answer)
    fireEvent.click(within(alert).getByRole('button', { name: 'Try again' }))
    expect(
      await screen.findByRole('region', { name: 'Expenses' }),
    ).toBeInTheDocument()
  })
})
