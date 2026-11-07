import { CircleCheck, WalletMinimal } from 'lucide-react'
import { useSearchParams } from 'react-router'
import { signInUrl } from '@/api/session'
import { Button } from '@/components/ui/button'

const points = [
  'Edits never erase history: every change is kept.',
  'No ads and no third-party trackers.',
]

/** Shown instead of the app while there is no session. */
export function SignInPage() {
  const [searchParams] = useSearchParams()
  // The API sends people back here with ?sign-in=failed, e.g. for an unverified Google email.
  const failed = searchParams.get('sign-in') === 'failed'

  return (
    <div className="flex min-h-svh flex-col bg-muted/40">
      <main className="flex flex-1 items-center justify-center px-4 pt-12 pb-6">
        <div className="flex w-full max-w-100 flex-col gap-6">
          <div className="flex flex-col items-center gap-2 text-center">
            <span className="grid size-12 place-items-center rounded-xl bg-primary text-primary-foreground">
              <WalletMinimal aria-hidden className="size-6" />
            </span>
            <h1 className="mt-3 font-heading text-2xl font-semibold tracking-tight">
              Sign in to MoneyTrail
            </h1>
            <p className="text-muted-foreground">
              Follow the trail of every cent: your accounts, your spending and
              where it all went.
            </p>
          </div>

          <div className="flex flex-col gap-3 rounded-xl border bg-card p-6">
            {failed && (
              <p
                role="alert"
                className="rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive"
              >
                Signing in did not work. Use a Google account with a verified
                email address and try again.
              </p>
            )}
            <Button asChild variant="outline" size="lg" className="h-11">
              <a href={signInUrl}>
                <GoogleLogo />
                Continue with Google
              </a>
            </Button>
            <p className="text-center text-sm text-muted-foreground">
              We only receive your name, email address and profile photo.
            </p>
          </div>

          <ul className="flex flex-col gap-2.5 px-2 text-sm text-muted-foreground">
            {points.map((point) => (
              <li key={point} className="flex items-start gap-2.5">
                <CircleCheck aria-hidden className="mt-0.5 size-4 flex-none" />
                <span>{point}</span>
              </li>
            ))}
          </ul>
        </div>
      </main>

      <footer className="px-6 pt-4 pb-6 text-center text-sm text-muted-foreground">
        Open source under the MIT licence ·{' '}
        <a
          href="https://github.com/Muzachio/moneytrail"
          className="underline-offset-4 hover:underline"
        >
          View on GitHub
        </a>
      </footer>
    </div>
  )
}

/** Google's "G", as its sign-in branding guidelines ask for on the button. */
function GoogleLogo() {
  return (
    <svg aria-hidden viewBox="0 0 48 48" className="size-4.5">
      <path
        fill="#EA4335"
        d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z"
      />
      <path
        fill="#4285F4"
        d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z"
      />
      <path
        fill="#FBBC05"
        d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z"
      />
      <path
        fill="#34A853"
        d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z"
      />
    </svg>
  )
}
