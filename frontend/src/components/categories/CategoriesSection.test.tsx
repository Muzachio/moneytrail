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
  const categories: Category[] = defaultCategories().map(
    ({ key, ...category }) =>
      // A typed name instead of the known one: the API sends one of them, never both.
      key === 'food'
        ? { ...category, name: 'Groceries' }
        : { ...category, key },
  )
  categories.splice(
    8,
    0,
    testCategory('EXPENSE', { name: 'Pets', icon: 'paw-print' }),
  )
  return categories
}

function list(name: string) {
  return screen.getByRole('region', { name })
}

function row(name: string) {
  return screen.getByText(name, { selector: 'li *' }).closest('li')!
}

/** The class of the icon in a category's row, which names the Lucide icon drawn. */
function iconOf(name: string) {
  return row(name).querySelector('svg')?.getAttribute('class')
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

  it("shows each category with the icon the API gives it: its key's for a default one, the one picked for the person's own", async () => {
    await renderCategories()

    expect(iconOf('Housing')).toContain('lucide-house')
    // Renaming keeps the key, and so the icon.
    expect(iconOf('Groceries')).toContain('lucide-utensils')
    expect(iconOf('Transport')).toContain('lucide-tram-front')
    expect(iconOf('Bills')).toContain('lucide-receipt')
    expect(iconOf('Shopping')).toContain('lucide-shopping-bag')
    expect(iconOf('Leisure')).toContain('lucide-ticket')
    expect(iconOf('Health')).toContain('lucide-heart')
    expect(iconOf('Other')).toContain('lucide-tag')
    expect(iconOf('Salary')).toContain('lucide-banknote')
    expect(iconOf('Refunds')).toContain('lucide-rotate-ccw')
    expect(iconOf('Gifts')).toContain('lucide-gift')
    expect(iconOf('Other income')).toContain('lucide-tag')
    expect(iconOf('Pets')).toContain('lucide-paw-print')
  })

  it('adds a category with the icon picked for it', async () => {
    const { post } = await renderCategories()

    fireEvent.click(
      screen.getByRole('button', { name: 'New expense category' }),
    )
    const dialog = await screen.findByRole('dialog', {
      name: 'New expense category',
    })
    const icons = within(dialog).getByRole('group', { name: 'Icon' })
    const radios = within(icons).getAllByRole('radio')
    expect(radios).toHaveLength(24)
    // Every icon is named, the same for screen readers and on hover.
    for (const radio of radios) {
      const title = radio.closest('label')!.title
      expect(title).not.toBe('')
      expect(radio).toHaveAccessibleName(title)
    }
    expect(within(icons).getByRole('radio', { name: 'Tag' })).toBeChecked()
    fireEvent.change(within(dialog).getByLabelText('Name'), {
      target: { value: 'Gym' },
    })
    fireEvent.click(within(icons).getByRole('radio', { name: 'Dumbbell' }))
    expect(within(icons).getByRole('radio', { name: 'Tag' })).not.toBeChecked()
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Add category' }),
    )

    await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(await within(list('Expenses')).findByText('Gym')).toBeVisible()
    expect(iconOf('Gym')).toContain('lucide-dumbbell')
    expect(post).toHaveBeenCalledExactlyOnceWith('/v1/categories', {
      body: { kind: 'EXPENSE', key: 'gym', icon: 'dumbbell' },
    })
  })

  it('names the icons in the current language', async () => {
    await renderCategories()
    await act(() => i18n.changeLanguage('pt-PT'))

    fireEvent.click(
      screen.getByRole('button', { name: 'Nova categoria de despesa' }),
    )
    const dialog = await screen.findByRole('dialog', {
      name: 'Nova categoria de despesa',
    })
    const icons = within(dialog).getByRole('group', { name: 'Ícone' })
    expect(within(icons).getByRole('radio', { name: 'Etiqueta' })).toBeChecked()
    const pawPrint = within(icons).getByRole('radio', { name: 'Pegada' })
    expect(pawPrint.closest('label')).toHaveAttribute('title', 'Pegada')
  })

  it("edits the icon of a category of the person's own, sending only what changed", async () => {
    const { patch } = await renderCategories()

    await chooseFromMenu('More actions for Pets', /Edit/)
    const dialog = await screen.findByRole('dialog', { name: 'Edit category' })
    expect(within(dialog).getByLabelText('Name')).toHaveValue('Pets')
    const icons = within(dialog).getByRole('group', { name: 'Icon' })
    expect(
      within(icons).getByRole('radio', { name: 'Paw print' }),
    ).toBeChecked()
    fireEvent.click(within(icons).getByRole('radio', { name: 'Heart' }))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))

    await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await vi.waitFor(() => expect(iconOf('Pets')).toContain('lucide-heart'))
    expect(patch).toHaveBeenCalledExactlyOnceWith('/v1/categories/{id}', {
      params: { path: { id: expect.any(String) } },
      body: { icon: 'heart' },
    })
  })

  it("edits the name and the icon of a category of the person's own at once", async () => {
    const { patch } = await renderCategories()

    await chooseFromMenu('More actions for Pets', /Edit/)
    const dialog = await screen.findByRole('dialog', { name: 'Edit category' })
    fireEvent.change(within(dialog).getByLabelText('Name'), {
      target: { value: 'Cats' },
    })
    fireEvent.click(within(dialog).getByRole('radio', { name: 'Gift' }))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))

    await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(await within(list('Expenses')).findByText('Cats')).toBeVisible()
    expect(iconOf('Cats')).toContain('lucide-gift')
    expect(patch).toHaveBeenCalledExactlyOnceWith('/v1/categories/{id}', {
      params: { path: { id: expect.any(String) } },
      body: { name: 'Cats', icon: 'gift' },
    })
  })

  it("sends nothing when a category of the person's own is saved unchanged", async () => {
    const { patch } = await renderCategories()

    await chooseFromMenu('More actions for Pets', /Edit/)
    const dialog = await screen.findByRole('dialog', { name: 'Edit category' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))

    await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(patch).not.toHaveBeenCalled()
  })

  it('offers no icons for a default category, which keeps the icon of its key', async () => {
    await renderCategories()

    await chooseFromMenu('More actions for Transport', /Rename/)
    const dialog = await screen.findByRole('dialog', {
      name: 'Rename category',
    })

    expect(within(dialog).queryByRole('group', { name: 'Icon' })).toBeNull()
    expect(within(dialog).queryByRole('radio')).toBeNull()
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
    // The tag the picker starts with, as the API would give it anyway.
    expect(post).toHaveBeenCalledExactlyOnceWith('/v1/categories', {
      body: { kind: 'INCOME', key: 'freelance', icon: 'tag' },
    })
    expect(iconOf('Freelance')).toContain('lucide-tag')
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

  it('creates a category with a name the app knows in Portuguese, which reads in English too', async () => {
    const { post } = await renderCategories()
    await act(() => i18n.changeLanguage('pt-PT'))

    fireEvent.click(
      screen.getByRole('button', { name: 'Nova categoria de despesa' }),
    )
    const dialog = await screen.findByRole('dialog', {
      name: 'Nova categoria de despesa',
    })
    fireEvent.change(within(dialog).getByLabelText('Nome'), {
      target: { value: 'ginasio' },
    })
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Adicionar categoria' }),
    )

    await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(await within(list('Despesas')).findByText('Ginásio')).toBeVisible()
    expect(post).toHaveBeenCalledExactlyOnceWith('/v1/categories', {
      body: { kind: 'EXPENSE', key: 'gym', icon: 'tag' },
    })
    await act(() => i18n.changeLanguage('en'))
    expect(within(list('Expenses')).getByText('Gym')).toBeVisible()
  })

  it('creates a category with a name the app knows in English, which reads in Portuguese too', async () => {
    const { post } = await renderCategories()

    fireEvent.click(screen.getByRole('button', { name: 'New income category' }))
    const dialog = await screen.findByRole('dialog', {
      name: 'New income category',
    })
    fireEvent.change(within(dialog).getByLabelText('Name'), {
      target: { value: 'Christmas allowance' },
    })
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Add category' }),
    )

    await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(
      await within(list('Income')).findByText('Christmas allowance'),
    ).toBeVisible()
    expect(post).toHaveBeenCalledWith('/v1/categories', {
      body: { kind: 'INCOME', key: 'christmas-allowance', icon: 'tag' },
    })
    await act(() => i18n.changeLanguage('pt-PT'))
    expect(
      within(list('Receitas')).getByText('Subsídio de Natal'),
    ).toBeVisible()
  })

  it('keeps a name typed in the other language as it is', async () => {
    const { post } = await renderCategories([...defaultCategories()])
    await act(() => i18n.changeLanguage('pt-PT'))

    fireEvent.click(
      screen.getByRole('button', { name: 'Nova categoria de despesa' }),
    )
    const dialog = await screen.findByRole('dialog', {
      name: 'Nova categoria de despesa',
    })
    fireEvent.change(within(dialog).getByLabelText('Nome'), {
      target: { value: 'Pets' },
    })
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Adicionar categoria' }),
    )

    await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(await within(list('Despesas')).findByText('Pets')).toBeVisible()
    expect(post).toHaveBeenCalledWith('/v1/categories', {
      body: { kind: 'EXPENSE', name: 'Pets', icon: 'tag' },
    })
    await act(() => i18n.changeLanguage('en'))
    expect(within(list('Expenses')).getByText('Pets')).toBeVisible()
  })

  it('says a known name is taken in every language: no second «Alimentação» next to the default', async () => {
    await renderCategories([...defaultCategories()])
    await act(() => i18n.changeLanguage('pt-PT'))

    fireEvent.click(
      screen.getByRole('button', { name: 'Nova categoria de despesa' }),
    )
    const dialog = await screen.findByRole('dialog', {
      name: 'Nova categoria de despesa',
    })
    const name = within(dialog).getByLabelText('Nome')
    fireEvent.change(name, { target: { value: 'Alimentação' } })
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Adicionar categoria' }),
    )

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'Já tens uma categoria de despesa com este nome.',
    )
    expect(name).toHaveFocus()
  })

  it('renaming a default category back to its translation in Portuguese translates it again', async () => {
    const { patch } = await renderCategories()
    await act(() => i18n.changeLanguage('pt-PT'))

    // Food, which the person had renamed Groceries.
    await chooseFromMenu('Mais ações para Groceries', /Mudar o nome/)
    const dialog = await screen.findByRole('dialog', {
      name: 'Mudar o nome da categoria',
    })
    fireEvent.change(within(dialog).getByLabelText('Nome'), {
      target: { value: 'Alimentação' },
    })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Guardar' }))

    await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(
      await within(list('Despesas')).findByText('Alimentação'),
    ).toBeVisible()
    expect(patch).toHaveBeenCalledExactlyOnceWith('/v1/categories/{id}', {
      params: { path: { id: expect.any(String) } },
      body: { key: 'food' },
    })
    await act(() => i18n.changeLanguage('en'))
    expect(within(list('Expenses')).getByText('Food')).toBeVisible()
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
