import { CircleCheck } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import { signInUrl } from '@/api/session'
import { GoogleLogo } from '@/components/GoogleLogo'
import { LanguageToggle } from '@/components/LanguageToggle'
import { Logo } from '@/components/Logo'
import { Button } from '@/components/ui/button'

const points = [
  'signIn.history',
  'signIn.noTrackers',
  'signIn.install',
] as const

/** Shown instead of the app while there is no session. */
export function SignInPage() {
  const { t } = useTranslation()
  const [searchParams] = useSearchParams()
  // The API sends people back here with ?sign-in=not-invited when their email is not on the
  // invite list, and ?sign-in=failed otherwise, e.g. for an unverified Google email.
  const outcome = searchParams.get('sign-in')
  const problem =
    outcome === 'not-invited'
      ? 'signIn.notInvited'
      : outcome === 'failed'
        ? 'signIn.failed'
        : null

  return (
    <div className="flex min-h-svh flex-col">
      <main className="flex flex-1 items-center justify-center px-4 pt-12 pb-6">
        <div className="flex w-full max-w-100 flex-col gap-6">
          <div className="flex flex-col items-center gap-2 text-center">
            <Logo className="size-12" />
            <h1 className="mt-3 font-heading text-2xl font-semibold tracking-tight">
              {t('signIn.title')}
            </h1>
            <p className="text-muted-foreground">{t('signIn.lead')}</p>
          </div>

          <div className="flex flex-col gap-3 rounded-xl border bg-card p-6">
            {problem && (
              <p
                role="alert"
                className="rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive"
              >
                {t(problem)}
              </p>
            )}
            <Button asChild variant="outline" size="lg" className="h-11">
              <a href={signInUrl}>
                <GoogleLogo />
                {t('signIn.google')}
              </a>
            </Button>
            <p className="text-center text-sm text-muted-foreground">
              {t('signIn.scope')}
            </p>
          </div>

          <ul className="flex flex-col gap-2.5 px-2 text-sm text-muted-foreground">
            {points.map((point) => (
              <li key={point} className="flex items-start gap-2.5">
                <CircleCheck aria-hidden className="mt-0.5 size-4 flex-none" />
                <span>{t(point)}</span>
              </li>
            ))}
          </ul>
        </div>
      </main>

      {/* The bottom padding keeps the footer above the home indicator of phones that have one. */}
      <footer className="flex flex-wrap items-center justify-center gap-x-5 gap-y-2 px-6 pt-4 pb-[calc(1.5rem+env(safe-area-inset-bottom))] text-center text-sm text-muted-foreground">
        <span>
          {t('signIn.license')} ·{' '}
          <a
            href="https://github.com/Muzachio/moneytrail"
            className="underline-offset-4 hover:underline"
          >
            {t('signIn.github')}
          </a>{' '}
          ·{' '}
          <Link to="/privacy" className="underline-offset-4 hover:underline">
            {t('signIn.privacy')}
          </Link>
        </span>
        <LanguageToggle />
      </footer>
    </div>
  )
}
