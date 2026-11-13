import { ArrowDownRight, ArrowUpRight } from 'lucide-react'
import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import type { AccountTotals } from '@/lib/accounts'
import { formatMoney } from '@/lib/money'
import { cn } from '@/lib/utils'

/** Net worth, how it moved this month, and what makes it up: assets less liabilities. */
export function NetWorthCard({ totals }: { totals: AccountTotals }) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  const titleId = useId()
  const change = totals.changeThisMonth.amountMinor
  const changeAmount = formatMoney(
    { ...totals.changeThisMonth, amountMinor: Math.abs(change) },
    language,
  )

  return (
    <section
      aria-labelledby={titleId}
      className="flex flex-wrap items-end justify-between gap-6 rounded-xl border bg-card p-5 sm:p-6"
    >
      <div className="min-w-0">
        <h2
          id={titleId}
          className="text-[13px] font-medium text-muted-foreground"
        >
          {t('accounts.netWorth.title')}
        </h2>
        <p className="mt-1 text-4xl leading-tight font-semibold tracking-tight tabular-nums sm:text-5xl">
          {formatMoney(totals.netWorth, language)}
        </p>
        <p
          className={cn(
            'mt-2.5 flex items-center gap-1.5 text-[13px] font-medium',
            change > 0 && 'text-income-text',
            change === 0 && 'text-muted-foreground',
          )}
        >
          {change > 0 && <ArrowUpRight aria-hidden className="size-4" />}
          {change < 0 && <ArrowDownRight aria-hidden className="size-4" />}
          {change > 0
            ? t('accounts.netWorth.up', { amount: changeAmount })
            : change < 0
              ? t('accounts.netWorth.down', { amount: changeAmount })
              : t('accounts.netWorth.same')}
        </p>
      </div>
      <dl className="flex flex-wrap gap-x-8 gap-y-3">
        <div>
          <dt className="text-[13px] text-muted-foreground">
            {t('accounts.netWorth.assets')}
          </dt>
          <dd className="mt-0.5 text-lg font-semibold tabular-nums">
            {formatMoney(totals.assets, language)}
          </dd>
        </div>
        <div className="border-l pl-8">
          <dt className="text-[13px] text-muted-foreground">
            {t('accounts.netWorth.liabilities')}
          </dt>
          <dd className="mt-0.5 text-lg font-semibold tabular-nums">
            {formatMoney(totals.liabilities, language)}
          </dd>
        </div>
      </dl>
    </section>
  )
}
