import {
  Archive,
  ArchiveRestore,
  ArrowLeft,
  EllipsisVertical,
  LogOut,
  Pencil,
  UserPlus,
} from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useParams } from 'react-router'
import {
  type Group,
  useGroup,
  useLeaveGroup,
  useUpdateGroup,
} from '@/api/groups'
import { ApiError } from '@/api/problem'
import { useCurrentUser } from '@/api/session'
import { InvitePeopleDialog } from '@/components/groups/InvitePeopleDialog'
import { NameDialog } from '@/components/NameDialog'
import { AvatarStack, PersonAvatar } from '@/components/PersonAvatar'
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
import { formatDate } from '@/lib/dates'
import { returnFocus } from '@/lib/focus'
import { apiErrorMessage } from '@/lib/forms'

/**
 * A group's page: its name, its members and the ways to invite people, rename, archive or leave
 * it. Its expenses, balances and activity join it in #92 and #93.
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

      <section
        aria-labelledby="group-members"
        className="flex flex-col rounded-xl border bg-card"
      >
        <h2
          id="group-members"
          className="px-5 pt-4.5 pb-1.5 text-[15px] font-semibold"
        >
          {t('group.membersTitle')}
        </h2>
        <ul>
          {members.map((member) => (
            <li
              key={member.userId}
              className="flex items-center gap-3 border-t px-5 py-3 first:border-t-0"
            >
              <PersonAvatar person={member} className="size-8 text-xs" />
              <span className="min-w-0">
                <span className="block truncate font-medium">
                  {you(member.userId)
                    ? t('group.youWithName', { name: member.name })
                    : member.name}
                </span>
                <span className="block text-sm text-muted-foreground">
                  {t('group.joined', {
                    date: formatDate(member.joinedAt, i18n.language),
                  })}
                </span>
              </span>
            </li>
          ))}
        </ul>
      </section>

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
