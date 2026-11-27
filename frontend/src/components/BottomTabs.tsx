import { ChartColumn, Landmark, List, Plus, Users } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { NavLink } from 'react-router'
import { cn } from '@/lib/utils'

const tab =
  'flex h-13 flex-col items-center justify-center gap-0.5 rounded-lg text-[11px] font-medium text-muted-foreground outline-none focus-visible:ring-3 focus-visible:ring-ring/50'

function tabClass({ isActive }: { isActive: boolean }) {
  return cn(tab, isActive && 'font-semibold text-foreground')
}

/**
 * The phone's navigation, as in the design canvas: Summary and Transactions, the new-transaction
 * button in the centre, then Groups and Accounts, within reach of the thumb. Groups took the place
 * of the "You" tab; the account menu is the avatar in the header (#91). The centre button opens the
 * same dialog as the header's button on wider screens.
 */
export function BottomTabs({
  onNewTransaction,
}: {
  onNewTransaction: (opener: HTMLElement) => void
}) {
  const { t } = useTranslation()

  return (
    <nav
      aria-label={t('nav.label')}
      // Clear of the home indicator of phones that have one, but not the whole inset below it: the
      // full 34 pt plus the bar's own padding left a gap that looked empty in the installed app.
      className="fixed inset-x-0 bottom-0 z-40 grid grid-cols-5 items-center border-t bg-background px-2 pt-1.5 pb-[max(0.375rem,calc(env(safe-area-inset-bottom)-0.75rem))]"
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
      <NavLink to="/groups" className={tabClass}>
        <Users aria-hidden className="size-5.5" />
        {t('nav.groups')}
      </NavLink>
      <NavLink to="/accounts" className={tabClass}>
        <Landmark aria-hidden className="size-5.5" />
        {t('nav.accounts')}
      </NavLink>
    </nav>
  )
}
