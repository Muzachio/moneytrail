import { Check, Copy, Link2, Lock, Share } from 'lucide-react'
import { useId, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  type Group,
  type InviteLink,
  type NewInviteLink,
  useCreateInvite,
  useInvites,
  useRevokeInvite,
} from '@/api/groups'
import { useCurrentUser } from '@/api/session'
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
import { formatShortDate, isToday } from '@/lib/dates'
import { returnFocus } from '@/lib/focus'
import { apiErrorMessage } from '@/lib/forms'
import { joinLink } from '@/lib/invites'

/**
 * The invite-people dialog of the design canvas: make a link, share or copy it while it is shown,
 * and see the group's active links, which any member can revoke (ADR-0013).
 */
export function InvitePeopleDialog({
  group,
  open,
  onOpenChange,
  returnFocusTo,
}: {
  group: Group
  open: boolean
  onOpenChange: (open: boolean) => void
  /** The button that opened the dialog, which gets the focus back when it closes. */
  returnFocusTo?: HTMLElement | null
}) {
  const { t } = useTranslation()
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        className="max-h-[calc(100svh-2rem)] gap-0 overflow-y-auto p-0 sm:max-w-130"
        onCloseAutoFocus={(event) => returnFocus(event, returnFocusTo)}
      >
        <DialogHeader className="gap-0.5 px-6 pt-5">
          <DialogTitle className="text-lg font-semibold">
            {t('invites.title')}
          </DialogTitle>
          <DialogDescription>
            {t('invites.to', { name: group.name })}
          </DialogDescription>
        </DialogHeader>
        {/* Inside the content: a link is shown once, so closing the dialog forgets it. */}
        <InvitePeople groupId={group.id} groupName={group.name} />
      </DialogContent>
    </Dialog>
  )
}

function InvitePeople({
  groupId,
  groupName,
}: {
  groupId: string
  groupName: string
}) {
  const { t } = useTranslation()
  const create = useCreateInvite(groupId)
  // Kept in this component only: the API shows a token once and keeps nothing but its hash.
  const [newLink, setNewLink] = useState<NewInviteLink | null>(null)

  const makeLink = () =>
    create.mutate(undefined, { onSuccess: (link) => setNewLink(link) })

  return (
    <>
      <div className="flex flex-col gap-4.5 px-6 pt-3.5 pb-5">
        <p className="text-foreground/75">{t('invites.intro')}</p>
        {newLink ? (
          <NewLink
            key={newLink.id}
            link={joinLink(newLink.token)}
            groupName={groupName}
          />
        ) : (
          <Button
            size="lg"
            className="h-11"
            onClick={makeLink}
            disabled={create.isPending}
          >
            <Link2 aria-hidden />
            {t('invites.create')}
          </Button>
        )}
        {create.isError && (
          <p
            role="alert"
            className="rounded-lg bg-destructive/10 px-3 py-2.5 text-sm text-destructive"
          >
            {t('invites.createFailed')} {apiErrorMessage(create.error, t)}
          </p>
        )}
        <ActiveLinks groupId={groupId} />
        <p className="flex items-start gap-2 rounded-lg bg-muted px-3 py-2.5 text-sm text-foreground/75">
          <Lock aria-hidden className="mt-0.5 size-4 flex-none" />
          {t('invites.privacy')}
        </p>
      </div>
      <DialogFooter className="mx-0 mb-0 rounded-b-xl px-6 py-4">
        {newLink && (
          <Button
            variant="ghost"
            size="lg"
            className="h-10 text-foreground/75"
            onClick={makeLink}
            disabled={create.isPending}
          >
            {t('invites.createAnother')}
          </Button>
        )}
        <DialogClose asChild>
          <Button variant="outline" size="lg" className="h-10 px-4.5">
            {t('invites.done')}
          </Button>
        </DialogClose>
      </DialogFooter>
    </>
  )
}

/** The link just made, while the dialog is open: shared through the phone's share sheet or copied. */
function NewLink({ link, groupName }: { link: string; groupName: string }) {
  const { t } = useTranslation()
  const headingId = useId()
  const input = useRef<HTMLInputElement>(null)
  const [copied, setCopied] = useState(false)
  // The Web Share API: phones and some desktop browsers. Elsewhere, copying is the way.
  const canShare = typeof navigator.share === 'function'

  const share = async () => {
    try {
      await navigator.share({
        title: 'MoneyTrail',
        text: t('invites.shareText', { name: groupName }),
        url: link,
      })
    } catch {
      // Closing the share sheet rejects too; there is nothing to tell the person.
    }
  }

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(link)
      setCopied(true)
    } catch {
      // No clipboard access (an old browser, or permission refused): select the link, so the
      // person can copy it themselves.
      input.current?.focus()
      input.current?.select()
    }
  }

  return (
    <section
      aria-labelledby={headingId}
      className="flex flex-col gap-3 rounded-xl border bg-muted/40 p-4"
    >
      <div className="flex items-center justify-between gap-3">
        <h3 id={headingId} className="font-semibold">
          {t('invites.newLink')}
        </h3>
        <Badge variant="outline" className="bg-background">
          {t('invites.shownOnce')}
        </Badge>
      </div>
      <input
        ref={input}
        readOnly
        value={link}
        aria-label={t('invites.linkLabel')}
        onFocus={(event) => event.currentTarget.select()}
        className="h-10 w-full truncate rounded-lg border bg-background px-3 font-mono text-[13px] outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50"
      />
      <div className={canShare ? 'grid grid-cols-2 gap-2' : 'grid gap-2'}>
        {canShare && (
          <Button size="lg" className="h-10" onClick={() => void share()}>
            <Share aria-hidden />
            {t('invites.share')}
          </Button>
        )}
        <Button
          variant={canShare ? 'outline' : 'default'}
          size="lg"
          className="h-10"
          onClick={() => void copy()}
        >
          {copied ? <Check aria-hidden /> : <Copy aria-hidden />}
          {copied ? t('invites.copied') : t('invites.copy')}
        </Button>
      </div>
      {/* Announced when the link is copied: the button's own change is easy to miss. */}
      <p role="status" className="sr-only">
        {copied ? t('invites.copiedStatus') : ''}
      </p>
      <p className="text-sm text-muted-foreground">{t('invites.copyNow')}</p>
    </section>
  )
}

/** The group's links that still work, with who made each and when it stops working. */
function ActiveLinks({ groupId }: { groupId: string }) {
  const { t } = useTranslation()
  const headingId = useId()
  const invites = useInvites(groupId)
  const revoke = useRevokeInvite(groupId)
  // Once for the list: each row asking would fetch /v1/me again as it appears.
  const me = useCurrentUser().data

  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-1">
      <h3 id={headingId} className="mb-1 font-semibold">
        {invites.isSuccess
          ? t('invites.active', { count: invites.data.length })
          : t('invites.activeTitle')}
      </h3>
      {invites.isPending && (
        <p role="status" className="text-sm text-muted-foreground">
          {t('invites.loading')}
        </p>
      )}
      {invites.isError && (
        <p role="alert" className="flex flex-wrap items-center gap-2 text-sm">
          {t('invites.loadFailed')}
          <Button
            variant="outline"
            size="sm"
            onClick={() => void invites.refetch()}
          >
            {t('common.tryAgain')}
          </Button>
        </p>
      )}
      {invites.isSuccess &&
        (invites.data.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t('invites.none')}</p>
        ) : (
          <ul className="border-t">
            {invites.data.map((invite) => (
              <ActiveLink
                key={invite.id}
                invite={invite}
                mine={invite.createdBy.userId === me?.id}
                revoking={revoke.isPending && revoke.variables === invite.id}
                onRevoke={() => revoke.mutate(invite.id)}
              />
            ))}
          </ul>
        ))}
      {revoke.isError && (
        <p role="alert" className="text-sm text-destructive">
          {t('invites.revokeFailed')} {apiErrorMessage(revoke.error, t)}
        </p>
      )}
    </section>
  )
}

function ActiveLink({
  invite,
  mine,
  revoking,
  onRevoke,
}: {
  invite: InviteLink
  /** Made by the signed-in person: "Created by you". */
  mine: boolean
  revoking: boolean
  onRevoke: () => void
}) {
  const { t, i18n } = useTranslation()
  const date = isToday(invite.createdAt)
    ? t('invites.today')
    : formatShortDate(invite.createdAt, i18n.language)
  const who = mine
    ? t('invites.byYou', { date })
    : t('invites.by', { name: invite.createdBy.name, date })

  return (
    <li className="grid min-h-14 grid-cols-[2rem_minmax(0,1fr)_auto] items-center gap-x-3 border-b">
      <span className="grid size-8 place-items-center rounded-full bg-muted text-foreground/75">
        <Link2 aria-hidden className="size-3.5" />
      </span>
      <span className="min-w-0">
        <span className="block truncate font-medium">{who}</span>
        <span className="block text-xs text-muted-foreground">
          {t('invites.expires', {
            date: formatShortDate(invite.expiresAt, i18n.language, {
              weekday: true,
            }),
          })}
        </span>
      </span>
      <Button
        variant="ghost"
        size="lg"
        className="text-destructive hover:text-destructive"
        aria-label={t('invites.revokeLabel', { who })}
        onClick={onRevoke}
        disabled={revoking}
      >
        {t('invites.revoke')}
      </Button>
    </li>
  )
}
