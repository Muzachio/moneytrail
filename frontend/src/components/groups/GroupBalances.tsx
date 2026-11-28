import { CircleCheck } from 'lucide-react'
import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import {
  type MemberBalance,
  useGroupBalances,
  useSettleUp,
} from '@/api/groupSettlements'
import type { Group } from '@/api/groups'
import { useCurrentUser } from '@/api/session'
import { PersonAvatar } from '@/components/PersonAvatar'
import { LoadFailed, Loading } from '@/components/QueryState'
import { Button } from '@/components/ui/button'
import { memberNames } from '@/lib/groupExpenses'
import { formatMoney, type Money, percentOf } from '@/lib/money'
import { cn } from '@/lib/utils'

/** "Rita and Pedro", without the American serial comma, as the group's page writes names. */
function listNames(names: readonly string[], language: string) {
  return new Intl.ListFormat(language === 'en' ? 'en-IE' : language, {
    style: 'long',
    type: 'conjunction',
  }).format(names)
}

/**
 * Every member's balance, as in the design canvas: who is owed comes first, with a bar to the right
 * of the line in the income colour, and who owes with a bar to the left in the spending colour,
 * each as long as their share of the largest balance. The amounts say the same in words, so the
 * bars are only drawn.
 */
export function GroupBalances({
  group,
  onSettleUp,
  className,
}: {
  group: Group
  /** Opens the settle-up dialog; absent for an archived group, which takes no payments. */
  onSettleUp?: (opener: HTMLElement) => void
  className?: string
}) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  const me = useCurrentUser().data?.id
  const balances = useGroupBalances(group.id)
  const titleId = useId()
  const names = memberNames(group.members, me, t)

  return (
    <section
      aria-labelledby={titleId}
      className={cn(
        'flex flex-col gap-3 rounded-[14px] border bg-card px-5 pt-4.5 pb-5',
        className,
      )}
    >
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id={titleId} className="text-[15px] font-semibold">
          {t('groupBalances.title')}
        </h2>
        <span
          aria-hidden
          className="inline-flex items-center gap-3 text-xs text-foreground/70"
        >
          <span className="inline-flex items-center gap-1.5">
            <span className="size-2 rounded-full bg-income" />
            {t('groupBalances.legend.owed')}
          </span>
          <span className="inline-flex items-center gap-1.5">
            <span className="size-2 rounded-full bg-spending" />
            {t('groupBalances.legend.owes')}
          </span>
        </span>
      </div>
      {balances.isPending ? (
        <Loading>{t('groupBalances.loading')}</Loading>
      ) : balances.isError ? (
        <LoadFailed onRetry={() => void balances.refetch()}>
          {t('groupBalances.loadFailed')}
        </LoadFailed>
      ) : (
        <BalanceList
          balances={balances.data}
          names={names}
          me={me}
          language={language}
        />
      )}
      {onSettleUp !== undefined && (
        <Button
          variant="outline"
          size="lg"
          className="h-10"
          onClick={(event) => onSettleUp(event.currentTarget)}
        >
          <CircleCheck aria-hidden />
          {t('settleUp.button')}
        </Button>
      )}
    </section>
  )
}

function BalanceList({
  balances,
  names,
  me,
  language,
}: {
  balances: MemberBalance[]
  names: ReturnType<typeof memberNames>
  me: string | undefined
  language: string
}) {
  const { t } = useTranslation()
  // Who is owed the most first, down to who owes the most; the API lists them as they joined.
  const sorted = balances.toSorted(
    (a, b) => b.balance.amountMinor - a.balance.amountMinor,
  )
  const largest = sorted.reduce(
    (max, { balance }) => Math.max(max, Math.abs(balance.amountMinor)),
    0,
  )

  return (
    <ul>
      {sorted.map(({ userId, balance }) => {
        const amount = balance.amountMinor
        const you = userId === me
        const label =
          amount > 0
            ? t(
                you
                  ? 'groupBalances.label.owedYou'
                  : 'groupBalances.label.owed',
              )
            : amount < 0
              ? t(
                  you
                    ? 'groupBalances.label.owesYou'
                    : 'groupBalances.label.owes',
                )
              : t('groupBalances.label.settled')
        const member = names.member(userId)
        return (
          <li
            key={userId}
            className="grid grid-cols-[1.75rem_minmax(5rem,1fr)_minmax(0,5rem)_auto] items-center gap-x-3 border-b border-border/60 py-2.25 last:border-b-0"
          >
            {member === undefined ? (
              <span aria-hidden className="size-7" />
            ) : (
              <PersonAvatar person={member} className="size-7 text-xs" />
            )}
            <span className="min-w-0">
              <span className="block truncate font-medium">
                {names.name(userId)}
              </span>
              <span className="block truncate text-xs whitespace-nowrap text-muted-foreground">
                {label}
              </span>
            </span>
            <BalanceBar balance={balance} largest={largest} />
            <span
              className={cn(
                'text-right font-semibold whitespace-nowrap tabular-nums',
                amount > 0 && 'text-income-text',
                amount === 0 && 'text-muted-foreground',
              )}
            >
              {formatMoney(balance, language, { signed: true })}
            </span>
          </li>
        )
      })}
    </ul>
  )
}

/** Half the width is each side of the line: a whole bar is the largest balance in the group. */
function BalanceBar({ balance, largest }: { balance: Money; largest: number }) {
  const amount = balance.amountMinor
  const percent =
    largest === 0
      ? 0
      : (percentOf(
          { amountMinor: Math.abs(amount), currency: balance.currency },
          { amountMinor: largest, currency: balance.currency },
        ) ?? 0)
  // A balance of a cent still shows a sliver.
  const half = amount === 0 ? 0 : Math.max(percent / 2, 1)
  return (
    <span aria-hidden className="relative block h-2">
      <span className="absolute -top-1.5 -bottom-1.5 left-1/2 w-px bg-border" />
      {half > 0 && (
        <span
          className={cn(
            'absolute top-0 h-2',
            amount > 0
              ? 'left-1/2 rounded-r-[4px] bg-income'
              : 'right-1/2 rounded-l-[4px] bg-spending',
          )}
          style={{ width: `${half}%` }}
        />
      )}
    </span>
  )
}

/**
 * The signed-in person's balance in the group, at the top of the group's page as in the design:
 * what they owe or are owed, and to or from whom in the suggested settle-up. On a phone it holds
 * the Settle up button, which a computer has in the page's header.
 */
export function MyBalanceCard({
  group,
  onSettleUp,
}: {
  group: Group
  onSettleUp?: (opener: HTMLElement) => void
}) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  const me = useCurrentUser().data?.id
  const balances = useGroupBalances(group.id)
  const settleUp = useSettleUp(group.id)
  const names = memberNames(group.members, me, t)

  // The balances section says when they cannot be loaded.
  const mine = balances.data?.find((balance) => balance.userId === me)
  if (mine === undefined) {
    return null
  }
  const amount = mine.balance.amountMinor
  const counterparts =
    settleUp.data
      ?.filter((payment) =>
        amount < 0 ? payment.from === me : payment.to === me,
      )
      .map((payment) => names.name(amount < 0 ? payment.to : payment.from)) ??
    []

  return (
    <section className="flex items-center justify-between gap-3 rounded-2xl border bg-card p-4 sm:rounded-[14px] sm:p-5">
      <div className="min-w-0">
        <h2 className="text-[13px] font-medium text-muted-foreground">
          {amount < 0
            ? t('groups.balance.owe')
            : amount > 0
              ? t('groups.balance.owed')
              : t('groups.balance.settled')}
        </h2>
        <p
          className={cn(
            'text-[32px] leading-tight font-semibold tracking-tight tabular-nums sm:text-[28px]',
            amount > 0 && 'text-income-text',
          )}
        >
          {formatMoney(
            { ...mine.balance, amountMinor: Math.abs(amount) },
            language,
          )}
        </p>
        <p className="mt-0.5 text-xs text-muted-foreground sm:text-[13px]">
          {amount === 0
            ? t('groupBalances.card.settled')
            : counterparts.length > 0
              ? t(
                  amount < 0
                    ? 'groupBalances.card.to'
                    : 'groupBalances.card.from',
                  { names: listNames(counterparts, language) },
                )
              : null}
        </p>
      </div>
      {onSettleUp !== undefined && (
        <Button
          variant="outline"
          size="lg"
          className="h-11 flex-none px-3.5 sm:hidden"
          onClick={(event) => onSettleUp(event.currentTarget)}
        >
          {t('settleUp.button')}
        </Button>
      )}
    </section>
  )
}
