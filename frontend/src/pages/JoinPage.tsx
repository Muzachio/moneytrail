import { Link2Off, Lock, Users } from 'lucide-react'
import { type ReactNode, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router'
import { useAcceptInvite, useInvitePreview } from '@/api/groups'
import { ApiError } from '@/api/problem'
import { signInUrl, useCurrentUser } from '@/api/session'
import { GoogleLogo } from '@/components/GoogleLogo'
import { LanguageToggle } from '@/components/LanguageToggle'
import { Logo } from '@/components/Logo'
import { PersonAvatar } from '@/components/PersonAvatar'
import { Button } from '@/components/ui/button'
import { apiErrorMessage } from '@/lib/forms'
import {
  clearReturnAfterSignIn,
  forgetPendingInvite,
  keepPendingInvite,
  markReturnAfterSignIn,
  pendingInvite,
  tokenFromHash,
} from '@/lib/invites'

/**
 * Where an invite link (`/join#<token>`) lands, with or without a session (ADR-0013). The token is
 * read from the fragment, which no server ever sees, kept in sessionStorage while the person signs
 * in with Google, and sent only in the bodies of the lookup and accept requests.
 */
export function JoinPage() {
  const location = useLocation()
  const navigate = useNavigate()
  // From the link itself, or, back from Google's sign-in, from this tab's storage.
  const [token] = useState(
    () => tokenFromHash(location.hash) ?? pendingInvite(),
  )

  useEffect(() => {
    clearReturnAfterSignIn()
    if (location.hash !== '') {
      if (token !== null) {
        keepPendingInvite(token)
      }
      // Off the address bar and out of the history entry, so the link is not left behind for
      // someone else using this browser, or in a bookmark.
      void navigate(
        { pathname: location.pathname, search: location.search },
        { replace: true },
      )
    }
    // Once, as the page opens: the token is read then.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <div className="flex min-h-svh flex-col">
      <header className="flex items-center gap-2 px-5 py-4 font-heading text-[17px] font-semibold">
        <Logo className="size-6" />
        MoneyTrail
      </header>
      <main className="flex flex-1 flex-col justify-center px-5 pb-12">
        <div className="mx-auto w-full max-w-100">
          {token === null ? <NoLongerWorks /> : <Invite token={token} />}
        </div>
      </main>
      {/* The bottom padding keeps the footer above the home indicator of phones that have one. */}
      <footer className="flex flex-wrap items-center justify-center gap-x-5 gap-y-2 px-5 pt-2 pb-[calc(1.75rem+env(safe-area-inset-bottom))] text-sm text-muted-foreground">
        <JoinFooterLinks />
        <LanguageToggle />
      </footer>
    </div>
  )
}

function JoinFooterLinks() {
  const { t } = useTranslation()
  return (
    <span>
      MoneyTrail ·{' '}
      <Link to="/privacy" className="underline-offset-4 hover:underline">
        {t('signIn.privacy')}
      </Link>
    </span>
  )
}

/** Signed in or not, and what the sign-in came back with. */
function Invite({ token }: { token: string }) {
  const { t } = useTranslation()
  const currentUser = useCurrentUser()
  const [searchParams] = useSearchParams()
  // As on the sign-in page: the API comes back with ?sign-in=not-invited or ?sign-in=failed.
  const outcome = searchParams.get('sign-in')

  if (currentUser.isPending) {
    return <Loading>{t('app.loading')}</Loading>
  }
  if (currentUser.isError) {
    return (
      <Problem
        icon={<Users aria-hidden className="size-6" />}
        title={t('join.failed.title')}
        text={t('app.loadFailed')}
      >
        <Button
          variant="outline"
          className="h-12 self-stretch text-[15px]"
          onClick={() => void currentUser.refetch()}
        >
          {t('common.tryAgain')}
        </Button>
      </Problem>
    )
  }
  if (currentUser.data === null) {
    return outcome === 'not-invited' ? (
      <NotLetIn token={token} />
    ) : (
      <SignedOut token={token} failed={outcome === 'failed'} />
    )
  }
  return <Preview token={token} />
}

/** Signing in from here comes back here, through the mark the app follows (AppLayout). */
function signInFromJoin(token: string) {
  keepPendingInvite(token)
  markReturnAfterSignIn()
}

function SignedOut({ token, failed }: { token: string; failed: boolean }) {
  const { t } = useTranslation()
  return (
    <Card>
      <span className="mb-2.5 grid size-13 place-items-center rounded-full bg-muted text-foreground/85">
        <Users aria-hidden className="size-6" />
      </span>
      <h1 className="font-heading text-[22px] leading-tight font-semibold tracking-tight">
        {t('join.signedOut.title')}
      </h1>
      <p className="text-muted-foreground">{t('join.signedOut.text')}</p>
      {failed && (
        <p
          role="alert"
          className="mt-2 self-stretch rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive"
        >
          {t('signIn.failed')}
        </p>
      )}
      <Button
        asChild
        variant="outline"
        className="mt-5 h-12 self-stretch text-[15px]"
      >
        <a href={signInUrl} onClick={() => signInFromJoin(token)}>
          <GoogleLogo />
          {t('signIn.google')}
        </a>
      </Button>
    </Card>
  )
}

/** Signed in with a Google account that is not on the app's invite list. */
function NotLetIn({ token }: { token: string }) {
  const { t } = useTranslation()
  return (
    <Problem
      icon={<Lock aria-hidden className="size-6" />}
      title={t('join.notLetIn.title')}
      text={t('join.notLetIn.text')}
    >
      <Button
        asChild
        variant="outline"
        className="h-12 self-stretch text-[15px]"
      >
        <a href={signInUrl} onClick={() => signInFromJoin(token)}>
          <GoogleLogo />
          {t('join.notLetIn.retry')}
        </a>
      </Button>
    </Problem>
  )
}

/** The group the link opens, with who sent it, and the one tap that joins it. */
function Preview({ token }: { token: string }) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const preview = useInvitePreview(token, true)
  const accept = useAcceptInvite()
  const gone =
    isNotFound(preview.error) || (accept.isError && isNotFound(accept.error))

  useEffect(() => {
    if (gone) {
      forgetPendingInvite()
    }
  }, [gone])

  if (gone) {
    return <NoLongerWorks />
  }
  if (preview.isPending) {
    return <Loading>{t('join.loading')}</Loading>
  }
  if (preview.isError) {
    return (
      <Problem
        icon={<Users aria-hidden className="size-6" />}
        title={t('join.failed.title')}
        text={apiErrorMessage(preview.error, t)}
      >
        <Button
          variant="outline"
          className="h-12 self-stretch text-[15px]"
          onClick={() => void preview.refetch()}
        >
          {t('common.tryAgain')}
        </Button>
      </Problem>
    )
  }

  const invite = preview.data
  const join = () =>
    accept.mutate(token, {
      onSuccess: (group) => {
        forgetPendingInvite()
        void navigate(`/groups/${group.id}`, { replace: true })
      },
    })

  return (
    <Card>
      <PersonAvatar
        person={invite.invitedBy}
        className="mb-2.5 size-12 text-[15px]"
      />
      <p className="text-foreground/75">
        {invite.alreadyMember
          ? t('join.preview.alreadyMember')
          : t('join.preview.invitedBy', { name: invite.invitedBy.name })}
      </p>
      <h1 className="font-heading text-[26px] leading-tight font-semibold tracking-tight break-words">
        {invite.groupName}
      </h1>
      <p className="text-muted-foreground">
        {t('groups.members', { count: invite.memberCount })} · {invite.currency}
      </p>
      {accept.isError && (
        <p
          role="alert"
          className="mt-2 self-stretch rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive"
        >
          {apiErrorMessage(accept.error, t)}
        </p>
      )}
      <Button
        className="mt-5 h-12 self-stretch text-[15px] font-semibold"
        onClick={join}
        disabled={accept.isPending}
      >
        {invite.alreadyMember ? t('join.preview.open') : t('join.preview.join')}
      </Button>
      {!invite.alreadyMember && (
        <p className="mt-3.5 text-sm text-muted-foreground">
          {t('join.preview.privacy')}
        </p>
      )}
    </Card>
  )
}

/**
 * Expired, revoked, of an archived group, or never valid: the API answers all of them the same
 * (ADR-0013), and so does this page.
 */
function NoLongerWorks() {
  const { t } = useTranslation()
  return (
    <Problem
      icon={<Link2Off aria-hidden className="size-6" />}
      title={t('join.gone.title')}
      text={t('join.gone.text')}
    >
      <Button
        asChild
        variant="outline"
        className="h-12 self-stretch text-[15px]"
      >
        <Link to="/" onClick={forgetPendingInvite}>
          {t('join.gone.home')}
        </Link>
      </Button>
    </Problem>
  )
}

function isNotFound(error: unknown) {
  return error instanceof ApiError && error.status === 404
}

function Card({ children }: { children: ReactNode }) {
  return (
    <section className="flex flex-col items-center gap-1.5 rounded-2xl border bg-card px-5.5 py-7 text-center">
      {children}
    </section>
  )
}

/** A card that says what went wrong, read out as soon as it appears. */
function Problem({
  icon,
  title,
  text,
  children,
}: {
  icon: ReactNode
  title: string
  text: string
  children: ReactNode
}) {
  return (
    <section
      role="alert"
      className="flex flex-col items-center gap-1.5 rounded-2xl border bg-card px-5.5 py-7 text-center"
    >
      <span className="mb-2.5 grid size-13 place-items-center rounded-full bg-muted text-foreground/75">
        {icon}
      </span>
      <h1 className="font-heading text-[22px] leading-tight font-semibold tracking-tight">
        {title}
      </h1>
      <p className="text-muted-foreground">{text}</p>
      <div className="mt-5 flex w-full flex-col">{children}</div>
    </section>
  )
}

function Loading({ children }: { children: string }) {
  return (
    <p role="status" className="py-6 text-center text-muted-foreground">
      {children}
    </p>
  )
}
