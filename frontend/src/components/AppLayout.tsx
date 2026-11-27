import { Plus } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Navigate, NavLink, Outlet, useLocation } from 'react-router'
import { useCurrentUser } from '@/api/session'
import { AccountMenu } from '@/components/AccountMenu'
import { ApiStatus } from '@/components/ApiStatus'
import { BottomTabs } from '@/components/BottomTabs'
import { Logo } from '@/components/Logo'
import type { AppOutletContext } from '@/components/newTransaction'
import { TransactionDialog } from '@/components/transactions/TransactionDialog'
import { Button } from '@/components/ui/button'
import { returningFromSignIn } from '@/lib/invites'
import { useWideScreen } from '@/lib/useWideScreen'
import { cn } from '@/lib/utils'
import { SignInPage } from '@/pages/SignInPage'
import { InstallPrompt } from '@/pwa/InstallPrompt'

const navItems = [
  { to: '/', label: 'nav.summary', end: true },
  { to: '/accounts', label: 'nav.accounts', end: false },
  { to: '/transactions', label: 'nav.transactions', end: false },
  { to: '/groups', label: 'nav.groups', end: false },
] as const

export function AppLayout() {
  const { t } = useTranslation()
  const location = useLocation()
  const currentUser = useCurrentUser()
  // On a phone the navigation and the new-transaction button move to the bottom tabs, as in the
  // design canvas; the header keeps the name, the API's status and the account menu.
  const wide = useWideScreen()
  const [adding, setAdding] = useState(false)
  // The header's button, the bottom tabs' one, or another one a page offers, such as the empty
  // list's.
  const [addOpener, setAddOpener] = useState<HTMLElement | null>(null)
  const outletContext: AppOutletContext = {
    newTransaction: (opener) => {
      setAddOpener(opener)
      setAdding(true)
    },
  }

  // Google sign-in always comes back to `/` (or `/?sign-in=not-invited`); when it started on the
  // join page, the person goes on to it, with the outcome, to see the group they were invited to.
  if (returningFromSignIn()) {
    return (
      <Navigate to={{ pathname: '/join', search: location.search }} replace />
    )
  }

  if (currentUser.data === null) {
    return <SignInPage />
  }
  const user = currentUser.data

  return (
    <div className="min-h-svh">
      <header className="border-b bg-background">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-6 gap-y-3 px-4 py-3 sm:px-6">
          <NavLink
            to="/"
            className="flex items-center gap-2 font-heading text-lg font-semibold"
          >
            <Logo className="size-6" />
            MoneyTrail
          </NavLink>
          {user && wide && (
            <nav aria-label={t('nav.label')} className="flex gap-1">
              {navItems.map((item) => (
                <NavLink
                  key={item.to}
                  to={item.to}
                  end={item.end}
                  className={({ isActive }) =>
                    cn(
                      'rounded-md px-3 py-1.5 text-sm font-medium text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground',
                      isActive && 'bg-accent text-accent-foreground',
                    )
                  }
                >
                  {t(item.label)}
                </NavLink>
              ))}
            </nav>
          )}
          <div className="ml-auto flex items-center gap-4">
            {user && wide && (
              <Button
                size="lg"
                className="px-3.5"
                onClick={(event) =>
                  outletContext.newTransaction(event.currentTarget)
                }
              >
                <Plus aria-hidden />
                {t('transactions.new')}
              </Button>
            )}
            <ApiStatus />
            {user && <AccountMenu user={user} />}
          </div>
        </div>
      </header>
      <main
        className={cn(
          'mx-auto max-w-6xl px-4 py-8 sm:px-6',
          // Room for the bottom tabs and their raised button, so the end of a page can be read.
          user && !wide && 'pt-5 pb-[calc(7rem+env(safe-area-inset-bottom))]',
        )}
      >
        {user ? (
          <>
            <InstallPrompt />
            <Outlet context={outletContext} />
          </>
        ) : currentUser.isError ? (
          <p role="alert" className="text-muted-foreground">
            {t('app.loadFailed')}
          </p>
        ) : (
          <p className="text-muted-foreground">{t('app.loading')}</p>
        )}
      </main>
      {user && !wide && (
        <BottomTabs onNewTransaction={outletContext.newTransaction} />
      )}
      {user && (
        <TransactionDialog
          open={adding}
          onOpenChange={setAdding}
          returnFocusTo={addOpener}
        />
      )}
    </div>
  )
}
