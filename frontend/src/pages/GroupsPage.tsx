import { Archive, Plus, Users } from 'lucide-react'
import { useState } from 'react'
import { Trans, useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router'
import { type GroupSummary, useGroups } from '@/api/groups'
import { ArchivedSection } from '@/components/ArchivedSection'
import { NewGroupDialog } from '@/components/groups/NewGroupDialog'
import { LoadFailed, Loading } from '@/components/QueryState'
import { Button } from '@/components/ui/button'
import { formatDate } from '@/lib/dates'
import { formatMoney, type Money, sumMoney } from '@/lib/money'
import { cn } from '@/lib/utils'

/** The Groups page of the design canvas: the person's groups, with their balance in each. */
export function GroupsPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [creating, setCreating] = useState(false)
  // Two New group buttons: the header's, and the empty state's.
  const [createOpener, setCreateOpener] = useState<HTMLElement | null>(null)
  const create = (opener: HTMLElement) => {
    setCreateOpener(opener)
    setCreating(true)
  }

  return (
    <div className="flex flex-col gap-4 sm:gap-6">
      <div className="flex flex-wrap items-center justify-between gap-4 sm:items-end">
        <div>
          <h1 className="font-heading text-2xl leading-tight font-semibold tracking-tight sm:text-[1.75rem]">
            {t('groups.title')}
          </h1>
          {/* On a phone the title and the button share one line, as in the design canvas. */}
          <p className="mt-1 text-muted-foreground max-sm:hidden">
            {t('groups.lead')}
          </p>
        </div>
        <Button
          variant="outline"
          size="lg"
          className="max-sm:h-10"
          onClick={(event) => create(event.currentTarget)}
        >
          <Plus aria-hidden />
          {t('groups.new.button')}
        </Button>
      </div>
      <Groups onCreate={create} />
      <NewGroupDialog
        open={creating}
        onOpenChange={setCreating}
        onCreated={(group) => void navigate(`/groups/${group.id}`)}
        returnFocusTo={createOpener}
      />
    </div>
  )
}

function Groups({ onCreate }: { onCreate: (opener: HTMLElement) => void }) {
  const { t, i18n } = useTranslation()
  const groups = useGroups()

  if (groups.isPending) {
    return <Loading>{t('groups.loading')}</Loading>
  }
  if (groups.isError) {
    return (
      <LoadFailed onRetry={() => void groups.refetch()}>
        {t('groups.loadFailed')}
      </LoadFailed>
    )
  }
  if (groups.data.length === 0) {
    return <NoGroups onCreate={onCreate} />
  }

  const active = groups.data.filter((group) => !group.archived)
  const archived = groups.data.filter((group) => group.archived)

  return (
    <>
      <BalanceSummary groups={active} />
      {active.length > 0 ? (
        <ul className="grid gap-2.5 sm:grid-cols-[repeat(auto-fill,minmax(18.75rem,1fr))] sm:gap-4">
          {active.map((group) => (
            <li key={group.id}>
              <GroupCard group={group} />
            </li>
          ))}
        </ul>
      ) : (
        <p className="rounded-xl border border-dashed bg-card p-4 text-muted-foreground">
          {t('groups.allArchived')}
        </p>
      )}
      {archived.length > 0 && (
        <ArchivedSection
          title={t('groups.archived.title', { count: archived.length })}
          headingLevel={2}
        >
          <ul className="flex flex-col gap-2.5">
            {archived.map((group) => (
              <li
                key={group.id}
                className="flex flex-wrap items-center gap-4 rounded-xl border bg-card px-4 py-3.5"
              >
                <span className="grid size-9 flex-none place-items-center rounded-lg bg-muted text-muted-foreground">
                  <Archive aria-hidden className="size-4.5" />
                </span>
                <span className="min-w-45 flex-1">
                  <span className="block font-medium text-foreground/75">
                    {group.name}
                  </span>
                  <span className="block text-sm text-muted-foreground">
                    {[
                      t('groups.members', { count: group.memberCount }),
                      group.archivedAt &&
                        t('groups.archived.on', {
                          date: formatDate(group.archivedAt, i18n.language),
                        }),
                      t('groups.archived.settled'),
                    ]
                      .filter(Boolean)
                      .join(' · ')}
                  </span>
                </span>
                <Button asChild variant="outline" size="lg">
                  <Link
                    to={`/groups/${group.id}`}
                    aria-label={t('groups.archived.openLabel', {
                      name: group.name,
                    })}
                  >
                    {t('groups.archived.open')}
                  </Link>
                </Button>
              </li>
            ))}
          </ul>
        </ArchivedSection>
      )}
    </>
  )
}

/**
 * How the person stands across their active groups: what they owe in some, what they are owed in
 * others. Each group keeps its own balance; nothing here is netted between groups.
 */
function BalanceSummary({ groups }: { groups: GroupSummary[] }) {
  const { i18n } = useTranslation()
  const currency = groups[0]?.myBalance.currency
  // Euros only for now (`CurrencyCode`); amounts of two currencies would need an exchange rate.
  if (
    currency === undefined ||
    groups.some((group) => group.myBalance.currency !== currency)
  ) {
    return null
  }
  const owing = groups.filter((group) => group.myBalance.amountMinor < 0)
  const owed = groups.filter((group) => group.myBalance.amountMinor > 0)
  const amount = (money: Money) => formatMoney(money, i18n.language)
  const emphasis = (
    <span className="font-semibold text-foreground tabular-nums" />
  )
  const owedEmphasis = (
    <span className="font-semibold text-income-text tabular-nums" />
  )
  const owe = sumMoney(
    currency,
    owing.map((group) => group.myBalance),
  )

  return (
    <p className="text-sm text-foreground/75 sm:text-base">
      {owing.length === 0 && owed.length === 0 ? (
        <Trans i18nKey="groups.summary.settled" />
      ) : (
        <>
          {owing.length > 0 && (
            <Trans
              i18nKey="groups.summary.owe"
              count={owing.length}
              values={{
                amount: amount({ ...owe, amountMinor: -owe.amountMinor }),
              }}
              components={{ amount: emphasis }}
            />
          )}
          {owing.length > 0 && owed.length > 0 && ' '}
          {owed.length > 0 && (
            <Trans
              i18nKey="groups.summary.owed"
              count={owed.length}
              values={{
                amount: amount(
                  sumMoney(
                    currency,
                    owed.map((group) => group.myBalance),
                  ),
                ),
              }}
              components={{ amount: owedEmphasis }}
            />
          )}
        </>
      )}
    </p>
  )
}

/**
 * A group in the list: a card on a wide screen, a row on a phone, as in the design canvas. The
 * amount is the person's balance in the group, coloured only when they are owed.
 */
function GroupCard({ group }: { group: GroupSummary }) {
  const { t, i18n } = useTranslation()
  const balance = group.myBalance.amountMinor
  const label =
    balance < 0
      ? t('groups.balance.owe')
      : balance > 0
        ? t('groups.balance.owed')
        : t('groups.balance.settled')

  return (
    <Link
      to={`/groups/${group.id}`}
      className="grid h-full grid-cols-[minmax(0,1fr)_auto] items-center gap-x-3 rounded-2xl border bg-card px-4 py-3.5 outline-none hover:border-foreground/35 focus-visible:ring-3 focus-visible:ring-ring/50 sm:flex sm:flex-col sm:items-stretch sm:gap-3.5 sm:rounded-xl sm:p-5"
    >
      <span className="min-w-0">
        <span className="block truncate text-[15px] font-semibold sm:text-base">
          {group.name}
        </span>
        <span className="block text-sm text-muted-foreground">
          {t('groups.members', { count: group.memberCount })} · {group.currency}
        </span>
      </span>
      <span className="text-right sm:text-left">
        <span className="block text-xs text-muted-foreground sm:text-sm">
          {label}
        </span>
        {balance !== 0 && (
          <span
            className={cn(
              'block text-[17px] font-semibold tabular-nums sm:text-2xl sm:leading-tight sm:tracking-tight',
              balance > 0 && 'text-income-text',
            )}
          >
            {formatMoney(
              { ...group.myBalance, amountMinor: Math.abs(balance) },
              i18n.language,
            )}
          </span>
        )}
      </span>
    </Link>
  )
}

function NoGroups({ onCreate }: { onCreate: (opener: HTMLElement) => void }) {
  const { t } = useTranslation()
  return (
    <section className="flex flex-col items-center gap-1.5 rounded-xl border border-dashed bg-card px-6 py-14 text-center">
      <span className="grid size-12 place-items-center rounded-full bg-muted text-foreground/75">
        <Users aria-hidden className="size-5.5" />
      </span>
      <h2 className="mt-2.5 font-heading text-lg font-semibold">
        {t('groups.empty.title')}
      </h2>
      <p className="max-w-105 text-muted-foreground">
        {t('groups.empty.text')}
      </p>
      <Button
        size="lg"
        className="mt-3.5 h-10 px-4"
        onClick={(event) => onCreate(event.currentTarget)}
      >
        <Plus aria-hidden />
        {t('groups.new.button')}
      </Button>
    </section>
  )
}
