import { useTranslation } from 'react-i18next'
import type { Account } from '@/api/accounts'
import {
  CardEmpty,
  CardLink,
  SummaryCard,
} from '@/components/summary/SummaryCard'
import { accountKind, accountTotals, accountTypeIcons } from '@/lib/accounts'
import { formatMoney } from '@/lib/money'

/**
 * The balance of each active account today, and the net worth under them, as in the design
 * canvas. The net worth counts archived accounts too, as on the accounts page: money left in one
 * is still the person's.
 */
export function AccountsCard({
  accounts,
  className,
}: {
  accounts: Account[]
  className?: string
}) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  const active = accounts.filter((account) => !account.archived)

  return (
    <SummaryCard
      title={t('summary.accounts.title')}
      action={
        <CardLink to="/accounts">{t('summary.accounts.manage')}</CardLink>
      }
      className={className}
    >
      {accounts.length === 0 ? (
        <CardEmpty>{t('summary.accounts.empty')}</CardEmpty>
      ) : (
        <div>
          <ul>
            {active.map((account) => {
              const Icon = accountTypeIcons[account.type]
              return (
                <li
                  key={account.id}
                  className="grid grid-cols-[2rem_minmax(0,1fr)_auto] items-center gap-x-3 border-b border-border/60 py-2.5"
                >
                  <span className="grid size-8 place-items-center rounded-lg bg-muted text-foreground/75">
                    <Icon aria-hidden className="size-4" />
                  </span>
                  <span className="min-w-0">
                    <span className="block font-medium wrap-break-word">
                      {account.name}
                    </span>
                    <span className="block text-[13px] text-muted-foreground">
                      {accountKind(account, t)}
                    </span>
                  </span>
                  <span className="font-medium whitespace-nowrap tabular-nums">
                    <span className="sr-only">
                      {t('accounts.columns.balance')}:{' '}
                    </span>
                    {formatMoney(account.balance, language)}
                  </span>
                </li>
              )
            })}
          </ul>
          <p className="flex items-baseline justify-between gap-3 pt-3.5">
            <span className="font-semibold">
              {t('summary.accounts.netWorth')}
            </span>
            <span className="text-lg font-semibold tabular-nums">
              {formatMoney(accountTotals(accounts).netWorth, language)}
            </span>
          </p>
        </div>
      )}
    </SummaryCard>
  )
}
