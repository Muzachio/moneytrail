import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '@/i18n/i18n'
import { testGroup, testMember } from '@/test/fakeGroups'
import { euros, mockApi, testUser } from '@/test/mockApi'
import { onPhone } from '@/test/phone'
import { renderApp } from '@/test/renderApp'

// Intl separates Portuguese amounts and the euro sign with a no-break space (U+00A0).
const NBSP = ' '

const me = testMember({ userId: testUser.id, name: testUser.name })

/** The groups of the design canvas: invented names and amounts. */
function designGroups() {
  return [
    testGroup({
      name: 'Porto weekend',
      members: [
        me,
        testMember({ name: 'João' }),
        testMember({ name: 'Pedro' }),
        testMember({ name: 'Rita' }),
      ],
      myBalance: euros(-5967),
    }),
    testGroup({
      name: 'Flat in Arroios',
      members: [
        me,
        testMember({ name: 'Inês' }),
        testMember({ name: 'Tiago' }),
      ],
      myBalance: euros(4210),
    }),
    testGroup({
      name: 'Friday dinners',
      members: [me, testMember({ name: 'Marta' })],
    }),
    testGroup({
      name: 'Algarve 2025',
      members: [me, testMember({ name: 'Carla' })],
      archived: true,
      archivedAt: '2025-09-02T12:00:00Z',
    }),
  ]
}

function card(name: string) {
  return screen.getByRole('link', { name: new RegExp(`^${name}`) })
}

describe('GroupsPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('shows each group with the balance in it, and what is owed across them', async () => {
    const groups = designGroups()
    mockApi({ groups })
    renderApp('/groups')

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Groups' }),
    ).toBeInTheDocument()
    await screen.findByText('Porto weekend')
    expect(card('Porto weekend')).toHaveTextContent(
      'Porto weekend4 members · EURYou owe€59.67',
    )
    expect(card('Porto weekend')).toHaveAttribute(
      'href',
      `/groups/${groups[0].id}`,
    )
    expect(card('Flat in Arroios')).toHaveTextContent('You are owed€42.10')
    expect(card('Friday dinners')).toHaveTextContent('All settled up')
    expect(screen.getByText(/You owe/, { selector: 'p' }).textContent).toBe(
      'You owe €59.67 in one group. You are owed €42.10 in one group.',
    )
  })

  it('keeps archived groups apart, closed until asked for', async () => {
    mockApi({ groups: designGroups() })
    renderApp('/groups')

    const toggle = await screen.findByRole('button', {
      name: 'Archived groups (1)',
    })
    expect(screen.getByText('Algarve 2025')).not.toBeVisible()
    fireEvent.click(toggle)
    expect(screen.getByText('Algarve 2025')).toBeVisible()
    expect(
      screen.getByText(
        '2 members · archived on 2 September 2025 · all settled up',
      ),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('link', { name: 'Open Algarve 2025' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /^Algarve 2025/ })).toBeNull()
  })

  it('offers to make the first group when there is none', async () => {
    mockApi()
    renderApp('/groups')

    expect(
      await screen.findByRole('heading', { name: 'No groups yet' }),
    ).toBeInTheDocument()
    fireEvent.click(
      within(
        screen.getByRole('heading', { name: 'No groups yet' }).parentElement!,
      ).getByRole('button', { name: 'New group' }),
    )
    expect(
      await screen.findByRole('dialog', { name: 'New group' }),
    ).toBeInTheDocument()
  })

  it('creates a group in euros with an Idempotency-Key and opens its page', async () => {
    const { post } = mockApi()
    renderApp('/groups')

    fireEvent.click(await screen.findByRole('button', { name: 'New group' }))
    const dialog = await screen.findByRole('dialog', { name: 'New group' })
    expect(within(dialog).getByLabelText('Currency')).toHaveDisplayValue(
      'EUR · Euro',
    )
    expect(
      within(dialog).getByText(
        'Every expense in the group is in this currency.',
      ),
    ).toBeInTheDocument()

    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Create group' }),
    )
    expect(await within(dialog).findByText('Enter a name.')).toBeInTheDocument()
    expect(post).not.toHaveBeenCalledWith('/v1/groups', expect.anything())

    fireEvent.change(within(dialog).getByLabelText('Name'), {
      target: { value: '  Porto weekend ' },
    })
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Create group' }),
    )

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Porto weekend' }),
    ).toBeInTheDocument()
    expect(post).toHaveBeenCalledWith('/v1/groups', {
      params: { header: { 'Idempotency-Key': expect.any(String) } },
      body: { name: 'Porto weekend', currency: 'EUR' },
    })
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('says when the groups could not be loaded, and tries again', async () => {
    const { get } = mockApi({ groups: designGroups() })
    const real = get.getMockImplementation()!
    let failed = false
    // The first list of groups fails, as when the API is restarting; the retry gets it.
    get.mockImplementation((async (path: string, ...rest: unknown[]) => {
      if (path === '/v1/groups' && !failed) {
        failed = true
        return {
          error: undefined,
          response: new Response(null, { status: 503 }),
        }
      }
      return (real as (...args: unknown[]) => unknown)(path, ...rest)
    }) as never)
    renderApp('/groups')

    expect(
      await screen.findByText('Your groups could not be loaded.'),
    ).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('Porto weekend')).toBeInTheDocument()
  })

  it('speaks European Portuguese', async () => {
    mockApi({ groups: designGroups() })
    renderApp('/groups')
    await screen.findByText('Porto weekend')
    await act(() => i18n.changeLanguage('pt-PT'))

    expect(
      screen.getByRole('heading', { level: 1, name: 'Grupos' }),
    ).toBeInTheDocument()
    expect(card('Porto weekend')).toHaveTextContent(
      '4 membros · EURDeves59,67 €',
    )
    expect(card('Flat in Arroios')).toHaveTextContent('Devem-te42,10 €')
    expect(card('Friday dinners')).toHaveTextContent('Contas acertadas')
    expect(screen.getByText(/Deves/, { selector: 'p' }).textContent).toBe(
      `Deves 59,67${NBSP}€ num grupo. Devem-te 42,10${NBSP}€ num grupo.`,
    )
  })

  describe('on a phone', () => {
    beforeEach(() => {
      onPhone()
    })

    afterEach(() => {
      vi.unstubAllGlobals()
    })

    it('is the Groups tab, next to Accounts', async () => {
      mockApi({ groups: designGroups() })
      renderApp('/groups')

      const tabs = await screen.findByRole('navigation', { name: 'Main' })
      expect(
        within(tabs).getByRole('link', { name: 'Groups' }),
      ).toHaveAttribute('aria-current', 'page')
      await waitFor(() =>
        expect(card('Porto weekend')).toHaveTextContent('You owe€59.67'),
      )
    })
  })
})
