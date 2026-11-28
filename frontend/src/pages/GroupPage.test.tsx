import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  testGroup,
  testMember,
  testToken,
  type TestGroup,
  type TestInvite,
} from '@/test/fakeGroups'
import { euros, mockApi, testUser } from '@/test/mockApi'
import { renderApp } from '@/test/renderApp'

const me = testMember({ userId: testUser.id, name: testUser.name })
const rita = testMember({ name: 'Rita' })

function portoWeekend(overrides: Partial<TestGroup> = {}) {
  return testGroup({
    name: 'Porto weekend',
    members: [rita, me, testMember({ name: 'João' })],
    ...overrides,
  })
}

/** A link Rita made, still working for a few days. */
function ritasInvite(groupId: string): TestInvite {
  return {
    id: crypto.randomUUID(),
    groupId,
    token: testToken('ritasLink'),
    createdBy: { userId: rita.userId, name: 'Rita' },
    createdAt: '2026-11-12T10:00:00Z',
    expiresAt: new Date(Date.now() + 3 * 86_400_000).toISOString(),
  }
}

/** Radix opens its menus on pointerdown, which jsdom does not send with a click; Enter works. */
async function chooseFromMenu(item: RegExp) {
  fireEvent.keyDown(
    screen.getByRole('button', { name: 'More actions for Porto weekend' }),
    { key: 'Enter' },
  )
  fireEvent.click(await screen.findByRole('menuitem', { name: item }))
}

async function openInviteDialog() {
  fireEvent.click(await screen.findByRole('button', { name: 'Invite people' }))
  return screen.findByRole('dialog', { name: 'Invite people' })
}

describe('GroupPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  afterEach(() => {
    // navigator.share and navigator.clipboard, where a test gave them.
    Reflect.deleteProperty(navigator, 'share')
    Reflect.deleteProperty(navigator, 'clipboard')
  })

  it('shows the group and its members, each with their balance', async () => {
    const group = portoWeekend()
    mockApi({ groups: [group] })
    renderApp(`/groups/${group.id}`)

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Porto weekend' }),
    ).toBeInTheDocument()
    expect(screen.getByText('You, Rita and João · EUR')).toBeInTheDocument()
    const balances = await within(
      screen.getByRole('region', { name: 'Balances' }),
    ).findAllByRole('listitem')
    // Nothing recorded yet: everyone is settled, in the order they joined.
    expect(balances.map((member) => member.textContent)).toEqual([
      'RRitasettled up€0.00',
      'ATYousettled up€0.00',
      'JJoãosettled up€0.00',
    ])
    // Back to the list, above the name; the navigation's Groups is marked as the current page.
    expect(
      within(screen.getByRole('main')).getByRole('link', { name: 'Groups' }),
    ).toHaveAttribute('href', '/groups')
    expect(
      within(screen.getByRole('navigation', { name: 'Main' })).getByRole(
        'link',
        { name: 'Groups' },
      ),
    ).toHaveAttribute('aria-current', 'page')
  })

  it('makes an invite link that carries the token only after #, to share or copy', async () => {
    const group = portoWeekend()
    const writeText = vi.fn(async () => {})
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText },
      configurable: true,
    })
    const { get, post, patch, remove } = mockApi({
      groups: [group],
      invites: [ritasInvite(group.id)],
    })
    renderApp(`/groups/${group.id}`)

    const dialog = await openInviteDialog()
    expect(within(dialog).getByText('to Porto weekend')).toBeInTheDocument()
    expect(
      await within(dialog).findByRole('heading', { name: 'Active links (1)' }),
    ).toBeInTheDocument()
    expect(
      within(dialog).getByText('Created by Rita, 12 Nov'),
    ).toBeInTheDocument()

    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Create invite link' }),
    )
    const link = await within(dialog).findByRole('textbox', {
      name: 'Invite link',
    })
    const token = (link as HTMLInputElement).value.split('#')[1]
    expect(link).toHaveValue(`${window.location.origin}/join#${token}`)
    expect(token).toMatch(/^[A-Za-z0-9_-]{43}$/)
    expect(within(dialog).getByText('Shown once')).toBeInTheDocument()
    expect(
      await within(dialog).findByRole('heading', { name: 'Active links (2)' }),
    ).toBeInTheDocument()
    expect(
      within(dialog).getByText('Created by you, today'),
    ).toBeInTheDocument()
    // No Web Share API in jsdom: copying is the way.
    expect(within(dialog).queryByRole('button', { name: 'Share…' })).toBeNull()

    fireEvent.click(within(dialog).getByRole('button', { name: 'Copy link' }))
    await waitFor(() =>
      expect(writeText).toHaveBeenCalledWith(
        `${window.location.origin}/join#${token}`,
      ),
    )
    expect(
      await within(dialog).findByRole('button', { name: 'Copied' }),
    ).toBeInTheDocument()

    // The token came back in the answer to the POST; no request carries it, in its path or
    // anywhere else (ADR-0013).
    for (const spy of [get, post, patch, remove]) {
      for (const call of spy.mock.calls) {
        expect(JSON.stringify(call)).not.toContain(token)
      }
    }

    // Closing the dialog forgets the link: it is shown once.
    fireEvent.click(within(dialog).getByRole('button', { name: 'Done' }))
    const reopened = await openInviteDialog()
    expect(within(reopened).queryByRole('textbox')).toBeNull()
    expect(
      within(reopened).getByRole('button', { name: 'Create invite link' }),
    ).toBeInTheDocument()
  })

  it("offers the phone's share sheet where there is one", async () => {
    const group = portoWeekend()
    const share = vi.fn(async () => {})
    Object.defineProperty(navigator, 'share', {
      value: share,
      configurable: true,
    })
    mockApi({ groups: [group] })
    renderApp(`/groups/${group.id}`)

    const dialog = await openInviteDialog()
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Create invite link' }),
    )
    fireEvent.click(
      await within(dialog).findByRole('button', { name: 'Share…' }),
    )

    const link = within(dialog).getByRole('textbox', { name: 'Invite link' })
    expect(share).toHaveBeenCalledWith({
      title: 'MoneyTrail',
      text: 'Join Porto weekend on MoneyTrail',
      url: (link as HTMLInputElement).value,
    })
  })

  it('revokes a link, which then leaves the list', async () => {
    const group = portoWeekend()
    const invite = ritasInvite(group.id)
    const { remove } = mockApi({ groups: [group], invites: [invite] })
    renderApp(`/groups/${group.id}`)

    const dialog = await openInviteDialog()
    fireEvent.click(
      await within(dialog).findByRole('button', {
        name: 'Revoke the link: Created by Rita, 12 Nov',
      }),
    )

    expect(
      await within(dialog).findByText(
        'No active links. Nobody new can join until someone creates one.',
      ),
    ).toBeInTheDocument()
    expect(remove).toHaveBeenCalledWith('/v1/groups/{id}/invites/{inviteId}', {
      params: { path: { id: group.id, inviteId: invite.id } },
    })
  })

  it('archives a group only when everyone is settled up', async () => {
    const group = portoWeekend({ myBalance: euros(-2100) })
    const { groups } = mockApi({ groups: [group] })
    renderApp(`/groups/${group.id}`)

    await screen.findByRole('heading', { level: 1, name: 'Porto weekend' })
    await chooseFromMenu(/Archive group/)
    expect(
      await screen.findByText(
        "The group could not be archived. Everyone's balance must be zero before the group can be archived. Settle up first.",
      ),
    ).toBeInTheDocument()

    groups.groups[0].myBalance = euros(0)
    await chooseFromMenu(/Archive group/)
    expect(await screen.findByText('Archived')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Invite people' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Restore group' }))
    expect(
      await screen.findByRole('button', { name: 'Invite people' }),
    ).toBeInTheDocument()
  })

  it('renames the group', async () => {
    const group = portoWeekend()
    mockApi({ groups: [group] })
    renderApp(`/groups/${group.id}`)

    await screen.findByRole('heading', { level: 1, name: 'Porto weekend' })
    await chooseFromMenu(/Rename/)
    const dialog = await screen.findByRole('dialog', { name: 'Rename group' })
    fireEvent.change(within(dialog).getByLabelText('Name'), {
      target: { value: 'Porto, May' },
    })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Porto, May' }),
    ).toBeInTheDocument()
  })

  it('leaves the group after asking, and only with a balance of zero', async () => {
    const group = portoWeekend({ myBalance: euros(1500) })
    const { groups } = mockApi({ groups: [group] })
    renderApp(`/groups/${group.id}`)

    await screen.findByRole('heading', { level: 1, name: 'Porto weekend' })
    await chooseFromMenu(/Leave group/)
    let dialog = await screen.findByRole('dialog', {
      name: 'Leave Porto weekend?',
    })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Leave group' }))
    expect(
      await within(dialog).findByText(
        'You can leave once your balance in this group is zero. Settle up first.',
      ),
    ).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())

    groups.groups[0].myBalance = euros(0)
    await chooseFromMenu(/Leave group/)
    dialog = await screen.findByRole('dialog', { name: 'Leave Porto weekend?' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Leave group' }))

    expect(
      await screen.findByRole('heading', { name: 'No groups yet' }),
    ).toBeInTheDocument()
  })

  it('says so when the person is not a member', async () => {
    const group = portoWeekend({ members: [rita] })
    mockApi({ groups: [group] })
    renderApp(`/groups/${group.id}`)

    expect(
      await screen.findByRole('heading', {
        name: 'This group is not available',
      }),
    ).toBeInTheDocument()
  })
})
