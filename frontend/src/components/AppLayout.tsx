import { WalletMinimal } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { NavLink, Outlet } from 'react-router'
import { useCurrentUser } from '@/api/session'
import { AccountMenu } from '@/components/AccountMenu'
import { ApiStatus } from '@/components/ApiStatus'
import { cn } from '@/lib/utils'
import { SignInPage } from '@/pages/SignInPage'

const navItems = [
  { to: '/', label: 'nav.summary', end: true },
  { to: '/accounts', label: 'nav.accounts', end: false },
  { to: '/transactions', label: 'nav.transactions', end: false },
] as const

export function AppLayout() {
  const { t } = useTranslation()
  const currentUser = useCurrentUser()

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
            <WalletMinimal aria-hidden className="size-5" />
            MoneyTrail
          </NavLink>
          {user && (
            <nav
              aria-label={t('nav.label')}
              className="order-last flex w-full gap-1 sm:order-none sm:w-auto"
            >
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
            <ApiStatus />
            {user && <AccountMenu user={user} />}
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-8 sm:px-6">
        {user ? (
          <Outlet />
        ) : currentUser.isError ? (
          <p role="alert" className="text-muted-foreground">
            {t('app.loadFailed')}
          </p>
        ) : (
          <p className="text-muted-foreground">{t('app.loading')}</p>
        )}
      </main>
    </div>
  )
}
