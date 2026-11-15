import { ChartColumn, Landmark, List, Plus } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { NavLink } from 'react-router'
import type { CurrentUser } from '@/api/session'
import { AccountMenu } from '@/components/AccountMenu'
import { cn } from '@/lib/utils'

const tab =
  'flex h-13 flex-col items-center justify-center gap-0.5 rounded-lg text-[11px] font-medium text-muted-foreground outline-none focus-visible:ring-3 focus-visible:ring-ring/50'

function tabClass({ isActive }: { isActive: boolean }) {
  return cn(tab, isActive && 'font-semibold text-foreground')
}

/**
 * The phone's navigation, as in the design canvas: Summary, Transactions, the new-transaction
 * button in the centre, Accounts and You, within reach of the thumb. The centre button opens the
 * same dialog as the header's button on wider screens.
 */
export function BottomTabs({
  user,
  onNewTransaction,
}: {
  user: CurrentUser
  onNewTransaction: (opener: HTMLElement) => void
}) {
  const { t } = useTranslation()

  return (
    <nav
      aria-label={t('nav.label')}
      // The bottom padding keeps the tabs above the home indicator of phones that have one.
      className="fixed inset-x-0 bottom-0 z-40 grid grid-cols-5 items-center border-t bg-background px-2 pt-1.5 pb-[calc(0.375rem+env(safe-area-inset-bottom))]"
    >
      <NavLink to="/" end className={tabClass}>
        <ChartColumn aria-hidden className="size-5.5" />
        {t('nav.summary')}
      </NavLink>
      <NavLink to="/transactions" className={tabClass}>
        <List aria-hidden className="size-5.5" />
        {t('nav.transactions')}
      </NavLink>
      <button
        type="button"
        aria-label={t('transactions.new')}
        onClick={(event) => onNewTransaction(event.currentTarget)}
        // Raised above the bar, with a ring of the bar's colour around it, as in the design canvas.
        className="-mt-5.5 grid size-13 place-items-center justify-self-center rounded-full bg-primary text-primary-foreground shadow-[0_0_0_4px_var(--background),0_8px_18px_rgb(0_0_0/0.18)] outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
      >
        <Plus aria-hidden className="size-6" />
      </button>
      <NavLink to="/accounts" className={tabClass}>
        <Landmark aria-hidden className="size-5.5" />
        {t('nav.accounts')}
      </NavLink>
      <AccountMenu user={user} tab className={tab} />
    </nav>
  )
}
