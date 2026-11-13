import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import type { Account } from '@/api/accounts'
import { RowMenu } from '@/components/RowMenu'
import { accountKind, accountTypeIcons, balanceOf } from '@/lib/accounts'
import { formatMoney } from '@/lib/money'
import { cn } from '@/lib/utils'

/**
 * A group of active accounts, as "Cash and bank" or "Credit cards" in the design canvas: each with
 * this month's change and its balance, and the group's total.
 */
export function AccountGroup({
  title,
  accounts,
  onRename,
  onArchive,
}: {
  title: string
  accounts: Account[]
  onRename: (account: Account, menuButton: HTMLElement | null) => void
  onArchive: (account: Account) => void
}) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  const titleId = useId()

  return (
    <section aria-labelledby={titleId} className="flex flex-col gap-2.5">
      <div className="flex items-baseline justify-between gap-3 px-1">
        <h2 id={titleId} className="text-[15px] font-semibold">
          {title}
        </h2>
        <span className="font-semibold tabular-nums">
          {formatMoney(balanceOf(accounts), language)}
        </span>
      </div>
      <div className="rounded-xl border bg-card">
        {/* Column titles for sighted people; each value carries its own for screen readers. */}
        <div
          aria-hidden
          className="flex items-center gap-3 border-b px-4 py-2.5 text-xs font-medium text-muted-foreground sm:gap-4"
        >
          <span className="flex-1 pl-12 sm:pl-13">
            {t('accounts.columns.account')}
          </span>
          <span className="hidden w-35 text-right sm:block">
            {t('accounts.columns.thisMonth')}
          </span>
          <span className="text-right sm:w-35">
            {t('accounts.columns.balance')}
          </span>
          <span className="w-9" />
        </div>
        <ul className="divide-y divide-border/60">
          {accounts.map((account) => (
            <AccountRow
              key={account.id}
              account={account}
              onRename={(menuButton) => onRename(account, menuButton)}
              onArchive={() => onArchive(account)}
            />
          ))}
        </ul>
      </div>
    </section>
  )
}

function AccountRow({
  account,
  onRename,
  onArchive,
}: {
  account: Account
  onRename: (menuButton: HTMLElement | null) => void
  onArchive: () => void
}) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  const Icon = accountTypeIcons[account.type]
  const change = account.changeThisMonth.amountMinor

  return (
    <li className="flex items-center gap-3 px-4 py-3 sm:gap-4">
      <span className="grid size-9 flex-none place-items-center rounded-[10px] bg-muted text-foreground/80">
        <Icon aria-hidden className="size-4.5" />
      </span>
      <span className="min-w-0 flex-1">
        {/* Wraps rather than ending in "…": on a phone the whole name still shows. */}
        <span className="block font-medium wrap-break-word">
          {account.name}
        </span>
        <span className="block text-[13px] text-muted-foreground">
          {accountKind(account, t)}
        </span>
      </span>
      {/* Left out on phones, as in the design canvas: the balance matters more there. */}
      <span
        className={cn(
          'hidden w-35 text-right tabular-nums sm:block',
          change > 0 && 'text-income-text',
          change === 0 && 'text-muted-foreground',
        )}
      >
        <span className="sr-only">{t('accounts.columns.thisMonth')}: </span>
        {change === 0
          ? t('accounts.noChange')
          : formatMoney(account.changeThisMonth, language, { signed: true })}
      </span>
      <span className="text-right font-semibold whitespace-nowrap tabular-nums sm:w-35">
        <span className="sr-only">{t('accounts.columns.balance')}: </span>
        {formatMoney(account.balance, language)}
      </span>
      <RowMenu
        label={t('rowMenu.label', { name: account.name })}
        archiveHint={t('accounts.archiveHint')}
        onRename={onRename}
        onArchive={onArchive}
      />
    </li>
  )
}
