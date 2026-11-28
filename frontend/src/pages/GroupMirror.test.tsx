import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { testGroup, testMember, type TestGroup } from '@/test/fakeGroups'
import { euros, mockApi, testAccount, testUser } from '@/test/mockApi'
import { renderApp } from '@/test/renderApp'

const me = testMember({ userId: testUser.id, name: testUser.name })

function portoWeekend(overrides: Partial<TestGroup> = {}) {
  return testGroup({
    name: 'Porto weekend',
    members: [testMember({ name: 'Rita' }), me],
    ...overrides,
  })
}

/** Radix opens its menus on pointerdown, which jsdom does not send with a click; Enter works. */
async function openMirrorDialog() {
  fireEvent.keyDown(
    await screen.findByRole('button', {
      name: 'More actions for Porto weekend',
    }),
    { key: 'Enter' },
  )
  fireEvent.click(
    await screen.findByRole('menuitem', { name: 'Record in my accounts' }),
  )
  return screen.findByRole('dialog', { name: 'Record in my accounts' })
}

/** The mirror of a group in the person's own ledger (#94), from the group's page. */
describe('GroupPage, recording my share in my accounts', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('turns it on with the chosen account, and the group account then shows on the accounts page', async () => {
    const group = portoWeekend({ myBalance: euros(-2100) })
    const checking = testAccount({ name: 'Everyday checking' })
    const savings = testAccount({ name: 'Savings', type: 'SAVINGS' })
    const { put, mirrors } = mockApi({
      groups: [group],
      accounts: [checking, savings],
    })
    renderApp(`/groups/${group.id}`)

    const dialog = await openMirrorDialog()
    expect(
      await within(dialog).findByText(/Earlier expenses come in only/),
    ).toBeInTheDocument()
    fireEvent.change(within(dialog).getByLabelText('Paid from'), {
      target: { value: savings.id },
    })
    expect(
      within(dialog).getByRole('option', { name: 'Other' }),
    ).toHaveProperty('selected', true)
    fireEvent.click(within(dialog).getByRole('button', { name: 'Turn on' }))

    await waitFor(() =>
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument(),
    )
    expect(put).toHaveBeenCalledWith(
      '/v1/groups/{id}/mirror',
      expect.objectContaining({
        params: expect.objectContaining({
          header: { 'Idempotency-Key': expect.any(String) },
        }) as unknown,
        body: expect.objectContaining({ accountId: savings.id }) as unknown,
      }),
    )
    expect(mirrors.get(group.id)).toMatchObject({ enabled: true })

    renderApp('/accounts')
    const groups = await screen.findByRole('region', { name: 'Groups' })
    expect(within(groups).getByText('Porto weekend')).toBeInTheDocument()
    expect(within(groups).getByText('Group · you owe')).toBeInTheDocument()
  })

  it('turns it off again', async () => {
    const group = portoWeekend()
    const checking = testAccount({ name: 'Everyday checking' })
    const { remove, mirrors } = mockApi({
      groups: [group],
      accounts: [checking],
    })
    renderApp(`/groups/${group.id}`)

    let dialog = await openMirrorDialog()
    fireEvent.click(
      await within(dialog).findByRole('button', { name: 'Turn on' }),
    )
    await waitFor(() =>
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument(),
    )

    dialog = await openMirrorDialog()
    expect(await within(dialog).findByText(/It is on/)).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Turn off' }))

    await waitFor(() =>
      expect(mirrors.get(group.id)).toMatchObject({ enabled: false }),
    )
    expect(remove).toHaveBeenCalledWith(
      '/v1/groups/{id}/mirror',
      expect.anything(),
    )
  })

  it('asks for an account of their own first', async () => {
    const group = portoWeekend()
    mockApi({ groups: [group], accounts: [] })
    renderApp(`/groups/${group.id}`)

    const dialog = await openMirrorDialog()

    expect(
      await within(dialog).findByText(
        'You need an account of your own for this.',
      ),
    ).toBeInTheDocument()
    expect(
      within(dialog).queryByRole('button', { name: 'Turn on' }),
    ).not.toBeInTheDocument()
  })
})
