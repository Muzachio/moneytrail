import {
  Archive,
  ArchiveRestore,
  ArrowLeft,
  CircleCheck,
  EllipsisVertical,
  LogOut,
  Pencil,
  Plus,
  UserPlus,
} from 'lucide-react'
import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, Outlet, useNavigate, useParams } from 'react-router'
import {
  type Group,
  useGroup,
  useLeaveGroup,
  useUpdateGroup,
} from '@/api/groups'
import { ApiError } from '@/api/problem'
import { useCurrentUser } from '@/api/session'
import { GroupActivity } from '@/components/groups/GroupActivity'
import { GroupBalances, MyBalanceCard } from '@/components/groups/GroupBalances'
import { GroupEntries } from '@/components/groups/GroupEntries'
import { GroupExpenseDialog } from '@/components/groups/GroupExpenseDialog'
import { InvitePeopleDialog } from '@/components/groups/InvitePeopleDialog'
import { SettleUpDialog } from '@/components/groups/SettleUpDialog'
import { NameDialog } from '@/components/NameDialog'
import { useSetCentreAction } from '@/components/newTransaction'
import { AvatarStack } from '@/components/PersonAvatar'
import { LoadFailed, Loading } from '@/components/QueryState'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { returnFocus } from '@/lib/focus'
import { apiErrorMessage } from '@/lib/forms'
import { cn } from '@/lib/utils'

/** What a phone shows of the group's page, one at a time as in the design canvas. */
const phoneTabs = ['expenses', 'balances', 'activity'] as const
type PhoneTab = (typeof phoneTabs)[number]

/**
 * A group's page: its name, the signed-in person's balance, its expenses and payments, everyone's
 * balances and what happened, and the ways to add an expense, settle up, invite people, rename,
 * archive or leave it.
 */
export function GroupPage() {
  const { t } = useTranslation()
  const { id = '' } = useParams()
  const group = useGroup(id)

  return (
    <div className="flex flex-col gap-5">
      <Link
        to="/groups"
        className="inline-flex items-center gap-1.5 self-start rounded-md text-sm font-medium text-foreground/75 underline-offset-4 outline-none hover:underline focus-visible:ring-3 focus-visible:ring-ring/50"
      >
        <ArrowLeft aria-hidden className="size-4" />
        {t('groups.title')}
      </Link>
      {group.isPending ? (
        <Loading>{t('group.loading')}</Loading>
      ) : group.isError ? (
        group.error instanceof ApiError && group.error.status === 404 ? (
          <GroupNotFound />
        ) : (
          <LoadFailed onRetry={() => void group.refetch()}>
            {t('group.loadFailed')}
          </LoadFailed>
        )
      ) : (
        <GroupDetails group={group.data} />
      )}
    </div>
  )
}

function GroupNotFound() {
  const { t } = useTranslation()
  return (
    <section className="flex flex-col items-start gap-2 rounded-xl border bg-card p-6">
      <h1 className="font-heading text-xl font-semibold">
        {t('group.notFound.title')}
      </h1>
      <p className="text-muted-foreground">{t('group.notFound.text')}</p>
    </section>
  )
}

function GroupDetails({ group }: { group: Group }) {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const me = useCurrentUser().data
  // Two of them, so a failed rename, which the dialog shows, is not reported on the page too.
  const rename = useUpdateGroup(group.id)
  const archive = useUpdateGroup(group.id)
  const leave = useLeaveGroup(group.id)
  // A state, not a ref: the dialogs read it while rendering, to give the focus back to it.
  const [menuButton, setMenuButton] = useState<HTMLButtonElement | null>(null)
  const [inviting, setInviting] = useState(false)
  const [inviteOpener, setInviteOpener] = useState<HTMLElement | null>(null)
  const [renaming, setRenaming] = useState(false)
  const [leaving, setLeaving] = useState(false)
  const [adding, setAdding] = useState(false)
  const [addOpener, setAddOpener] = useState<HTMLElement | null>(null)
  const openAdd = useCallback((opener: HTMLElement) => {
    setAddOpener(opener)
    setAdding(true)
  }, [])
  const [settling, setSettling] = useState(false)
  const [settleOpener, setSettleOpener] = useState<HTMLElement | null>(null)
  const openSettleUp = (opener: HTMLElement) => {
    setSettleOpener(opener)
    setSettling(true)
  }
  // An archived group takes no payments, so it offers no settling up.
  const onSettleUp = group.archived ? undefined : openSettleUp
  const [phoneTab, setPhoneTab] = useState<PhoneTab>('expenses')
  // On a phone only the chosen part shows; a computer shows them all side by side.
  const onPhone = (tab: PhoneTab) => phoneTab !== tab && 'max-sm:hidden'

  // On a phone the bottom tabs' centre button adds an expense to this group, as in the design
  // canvas; an archived group takes none, so the button keeps its new transaction.
  const setCentreAction = useSetCentreAction()
  const addLabel = t('groupExpenses.addTo', { name: group.name })
  useEffect(() => {
    setCentreAction(group.archived ? null : { label: addLabel, run: openAdd })
    return () => setCentreAction(null)
  }, [setCentreAction, group.archived, addLabel, openAdd])

  const you = (userId: string) => userId === me?.id
  // The signed-in person first, as "You", then the others in the order they joined.
  const members = [
    ...group.members.filter((member) => you(member.userId)),
    ...group.members.filter((member) => !you(member.userId)),
  ]
  // `en` as `en-IE`, like the dates: "You, Rita and João", without the American serial comma.
  const names = new Intl.ListFormat(
    i18n.language === 'en' ? 'en-IE' : i18n.language,
    {
      style: 'long',
      type: 'conjunction',
    },
  ).format(
    members.map((member) =>
      you(member.userId) ? t('group.you') : member.name,
    ),
  )

  return (
    <>
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="flex min-w-0 flex-col gap-2">
          <div className="flex flex-wrap items-center gap-3">
            <h1 className="font-heading text-2xl leading-tight font-semibold tracking-tight break-words sm:text-[1.75rem]">
              {group.name}
            </h1>
            {group.archived && (
              <Badge variant="outline">{t('group.archivedBadge')}</Badge>
            )}
          </div>
          <div className="flex items-center gap-2.5 text-muted-foreground">
            <AvatarStack people={members} className="size-7 text-xs" />
            <span className="min-w-0">
              {names} · {group.currency}
            </span>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {!group.archived && (
            <Button
              variant="outline"
              size="lg"
              onClick={(event) => {
                setInviteOpener(event.currentTarget)
                setInviting(true)
              }}
            >
              <UserPlus aria-hidden />
              {t('invites.title')}
            </Button>
          )}
          {/* On a phone the balance card at the top holds Settle up, as in the design. */}
          {onSettleUp !== undefined && (
            <Button
              variant="outline"
              size="lg"
              className="max-sm:hidden"
              onClick={(event) => onSettleUp(event.currentTarget)}
            >
              <CircleCheck aria-hidden />
              {t('settleUp.button')}
            </Button>
          )}
          {/* On a phone the bottom tabs' centre button adds the expense, as in the design. */}
          {!group.archived && (
            <Button
              size="lg"
              className="max-sm:hidden"
              onClick={(event) => openAdd(event.currentTarget)}
            >
              <Plus aria-hidden />
              {t('groupExpenses.add')}
            </Button>
          )}
          {/* Not modal: a dialog opens as the menu closes, as with the rows' menus. */}
          <DropdownMenu modal={false}>
            <DropdownMenuTrigger asChild>
              <Button
                ref={setMenuButton}
                variant="outline"
                size="icon-lg"
                aria-label={t('group.menu', { name: group.name })}
              >
                <EllipsisVertical aria-hidden />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-55">
              <DropdownMenuItem onSelect={() => setRenaming(true)}>
                <Pencil aria-hidden />
                {t('group.rename')}
              </DropdownMenuItem>
              <DropdownMenuItem
                onSelect={() => archive.mutate({ archived: !group.archived })}
              >
                {group.archived ? (
                  <ArchiveRestore aria-hidden />
                ) : (
                  <Archive aria-hidden />
                )}
                {group.archived ? t('group.restore') : t('group.archive')}
              </DropdownMenuItem>
              <DropdownMenuItem
                variant="destructive"
                onSelect={() => {
                  leave.reset()
                  setLeaving(true)
                }}
              >
                <LogOut aria-hidden />
                {t('group.leave')}
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </div>

      {archive.isError && (
        <p
          role="alert"
          className="rounded-lg bg-destructive/10 px-3 py-2.5 text-destructive"
        >
          {t(
            archive.variables.archived
              ? 'group.archiveFailed'
              : 'group.restoreFailed',
          )}{' '}
          {apiErrorMessage(archive.error, t)}
        </p>
      )}

      {group.archived && (
        <p className="flex flex-wrap items-center gap-3 rounded-xl border bg-card p-4 text-foreground/75">
          <span className="min-w-0 flex-1">{t('group.archivedNote')}</span>
          <Button
            variant="outline"
            size="lg"
            onClick={() => archive.mutate({ archived: false })}
            disabled={archive.isPending}
          >
            <ArchiveRestore aria-hidden />
            {t('group.restore')}
          </Button>
        </p>
      )}

      <MyBalanceCard group={group} onSettleUp={onSettleUp} />

      <div
        role="group"
        aria-label={t('groupBalances.tabs.label')}
        className="grid grid-cols-3 gap-0.5 rounded-xl bg-muted p-0.75 sm:hidden"
      >
        {phoneTabs.map((tab) => (
          <button
            key={tab}
            type="button"
            aria-pressed={phoneTab === tab}
            onClick={() => setPhoneTab(tab)}
            className={cn(
              'h-9.5 rounded-[9px] font-medium text-muted-foreground outline-none focus-visible:ring-3 focus-visible:ring-ring/50',
              phoneTab === tab && 'bg-card text-foreground shadow-xs',
            )}
          >
            {t(`groupBalances.tabs.${tab}`)}
          </button>
        ))}
      </div>

      <div className="grid items-start gap-5 lg:grid-cols-[minmax(0,1.65fr)_minmax(0,1fr)]">
        <div className={cn('min-w-0', onPhone('expenses'))}>
          <GroupEntries
            group={group}
            onAdd={group.archived ? undefined : openAdd}
          />
        </div>
        <div className="flex min-w-0 flex-col gap-5">
          <GroupBalances
            group={group}
            onSettleUp={onSettleUp}
            className={cn(onPhone('balances'))}
          />
          <GroupActivity group={group} className={cn(onPhone('activity'))} />
        </div>
      </div>

      {!group.archived && (
        <GroupExpenseDialog
          open={adding}
          onOpenChange={setAdding}
          group={group}
          returnFocusTo={addOpener}
        />
      )}
      {!group.archived && (
        <SettleUpDialog
          open={settling}
          onOpenChange={setSettling}
          group={group}
          returnFocusTo={settleOpener}
        />
      )}
      {/* An expense's or payment's detail, `/groups/<id>/expenses/<id>`, as a panel over the page. */}
      <Outlet />

      <InvitePeopleDialog
        group={group}
        open={inviting}
        onOpenChange={setInviting}
        returnFocusTo={inviteOpener}
      />
      <NameDialog
        open={renaming}
        onOpenChange={setRenaming}
        title={t('group.renameTitle')}
        submitLabel={t('common.save')}
        initialName={group.name}
        onSave={({ name }) => rename.mutateAsync({ name })}
        returnFocusTo={menuButton}
      />
      <Dialog open={leaving} onOpenChange={setLeaving}>
        <DialogContent
          className="gap-5 p-6 sm:max-w-105"
          onCloseAutoFocus={(event) => returnFocus(event, menuButton)}
        >
          <DialogHeader>
            <DialogTitle className="text-lg font-semibold">
              {t('group.leaveTitle', { name: group.name })}
            </DialogTitle>
            <DialogDescription>{t('group.leaveText')}</DialogDescription>
          </DialogHeader>
          {leave.isError && (
            <p
              role="alert"
              className="rounded-lg bg-destructive/10 px-3 py-2.5 text-sm text-destructive"
            >
              {apiErrorMessage(leave.error, t)}
            </p>
          )}
          <DialogFooter className="mx-0 mb-0 border-0 bg-transparent p-0">
            <DialogClose asChild>
              <Button variant="outline" size="lg" className="h-10">
                {t('common.cancel')}
              </Button>
            </DialogClose>
            <Button
              variant="destructive"
              size="lg"
              className="h-10"
              disabled={leave.isPending}
              onClick={() =>
                leave.mutate(undefined, {
                  onSuccess: () => void navigate('/groups', { replace: true }),
                })
              }
            >
              {t('group.leave')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}
