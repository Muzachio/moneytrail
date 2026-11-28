import { Banknote, Plus } from 'lucide-react'
import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import {
  type GroupEntry,
  type GroupExpense,
  type Settlement,
  useGroupEntries,
} from '@/api/groupExpenses'
import type { Group } from '@/api/groups'
import { useCurrentUser } from '@/api/session'
import { GroupCategoryIcon } from '@/components/groups/GroupCategoryIcon'
import { LoadFailed, Loading } from '@/components/QueryState'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { type Day, dayHeading } from '@/lib/dates'
import { apiErrorMessage } from '@/lib/forms'
import {
  groupCategoryName,
  memberNames,
  myPart,
  paidLine,
  paymentTitle,
} from '@/lib/groupExpenses'
import { formatMoney } from '@/lib/money'
import { cn } from '@/lib/utils'

type Names = ReturnType<typeof memberNames>

/**
 * The group's expenses and payments of the design canvas, newest first: each day with its items,
 * each item with what it is to the signed-in person, and "Load more" for the next page.
 */
export function GroupEntries({
  group,
  onAdd,
}: {
  group: Group
  /** Opens the new-expense dialog from the empty list; absent for an archived group. */
  onAdd?: (opener: HTMLElement) => void
}) {
  const { t } = useTranslation()
  const me = useCurrentUser().data?.id
  const list = useGroupEntries(group.id)
  const titleId = useId()
  const names = memberNames(group.members, me, t)

  // A failed "Load more" keeps the pages already loaded: only a list with nothing to show fails.
  if (list.data === undefined) {
    return list.isError ? (
      <LoadFailed onRetry={() => void list.refetch()}>
        {t('groupExpenses.loadFailed')}
      </LoadFailed>
    ) : (
      <Loading>{t('groupExpenses.loading')}</Loading>
    )
  }

  const entries = list.data.pages.flatMap((page) => page.items)
  const days = byDay(entries)

  return (
    <section
      aria-labelledby={titleId}
      className="overflow-hidden rounded-[14px] border bg-card"
    >
      <div className="flex items-baseline justify-between gap-3 px-5 pt-4.5 pb-1.5">
        <h2 id={titleId} className="text-[15px] font-semibold">
          {t('groupExpenses.listTitle')}
        </h2>
        {entries.length > 0 && (
          <span className="text-xs text-muted-foreground">
            {t('groupExpenses.newestFirst')}
          </span>
        )}
      </div>
      {entries.length === 0 ? (
        <NothingYet onAdd={onAdd} />
      ) : (
        days.map(({ day, entries: dayEntries }) => (
          <DayGroup
            key={day}
            day={day}
            entries={dayEntries}
            names={names}
            me={me}
          />
        ))
      )}
      {(list.hasNextPage || list.isFetchNextPageError) && (
        <div className="flex flex-wrap items-center justify-end gap-3 border-t bg-muted/40 px-5 py-3">
          {list.hasNextPage && (
            <Button
              variant="outline"
              size="lg"
              disabled={list.isFetchingNextPage}
              onClick={() => void list.fetchNextPage()}
            >
              {list.isFetchingNextPage
                ? t('transactions.loadingMore')
                : t('transactions.loadMore')}
            </Button>
          )}
          {list.isFetchNextPageError && (
            <p role="alert" className="w-full text-[13px] text-destructive">
              {t('groupExpenses.loadMoreFailed')}{' '}
              {apiErrorMessage(list.error, t)}
            </p>
          )}
        </div>
      )}
    </section>
  )
}

function dayOf(entry: GroupEntry): Day {
  return (entry.expense ?? entry.settlement)!.occurredOn
}

/** The items, newest first as the API lists them, grouped by the day they happened. */
function byDay(entries: readonly GroupEntry[]) {
  const days: { day: Day; entries: GroupEntry[] }[] = []
  for (const entry of entries) {
    const last = days.at(-1)
    if (last?.day === dayOf(entry)) {
      last.entries.push(entry)
    } else {
      days.push({ day: dayOf(entry), entries: [entry] })
    }
  }
  return days
}

function DayGroup({
  day,
  entries,
  names,
  me,
}: {
  day: Day
  entries: GroupEntry[]
  names: Names
  me: string | undefined
}) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  const { label, date } = dayHeading(day, language, {
    today: t('transactions.today'),
    yesterday: t('transactions.yesterday'),
  })
  return (
    <section aria-label={`${label}, ${date}`}>
      <h3 className="px-5 pt-3 pb-1 text-[13px] font-semibold">
        {label}{' '}
        <span className="font-normal text-muted-foreground">{date}</span>
      </h3>
      <ul className="px-2 pb-1.5">
        {entries.map((entry) =>
          entry.expense !== undefined ? (
            <ExpenseRow
              key={entry.expense.id}
              expense={entry.expense}
              names={names}
              me={me}
            />
          ) : entry.settlement !== undefined ? (
            <PaymentRow
              key={entry.settlement.id}
              payment={entry.settlement}
              names={names}
              me={me}
            />
          ) : null,
        )}
      </ul>
    </section>
  )
}

function ExpenseRow({
  expense,
  names,
  me,
}: {
  expense: GroupExpense
  names: Names
  me: string | undefined
}) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  const part = myPart(expense, me)

  return (
    <li>
      <Link
        to={`expenses/${expense.id}`}
        className="grid grid-cols-[2.25rem_minmax(0,1fr)_auto] items-center gap-x-3 rounded-[10px] px-3 py-2.5 outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50"
      >
        <span className="grid size-9 place-items-center rounded-full bg-muted text-foreground/75">
          <GroupCategoryIcon
            category={expense.category}
            aria-hidden
            className="size-4"
          />
          <span className="sr-only">
            {groupCategoryName(expense.category, t)}
          </span>
        </span>
        <span className="min-w-0">
          <span className="flex min-w-0 items-center gap-2">
            <span className="truncate font-medium">{expense.description}</span>
            {expense.edited && (
              <Badge variant="outline" className="text-foreground/70">
                {t('transactions.edited')}
              </Badge>
            )}
          </span>
          <span className="block truncate text-[13px] text-muted-foreground">
            {paidLine(expense, names, me, t)}
          </span>
        </span>
        <span className="text-right whitespace-nowrap">
          <span className="block font-medium tabular-nums">
            {formatMoney(expense.amount, language)}
          </span>
          <span
            className={cn(
              'block text-[13px] tabular-nums',
              part.kind === 'lent' && 'text-income-text',
              part.kind === 'none' && 'text-muted-foreground',
            )}
          >
            {part.kind === 'none'
              ? t('groupExpenses.row.notInvolved')
              : t(`groupExpenses.row.${part.kind}`, {
                  amount: formatMoney(part.amount, language),
                })}
          </span>
        </span>
      </Link>
    </li>
  )
}

/** A payment between two members, which opens its detail, with its trail and Reverse. */
function PaymentRow({
  payment,
  names,
  me,
}: {
  payment: Settlement
  names: Names
  me: string | undefined
}) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  const involved = payment.from === me || payment.to === me
  return (
    <li>
      <Link
        to={`payments/${payment.id}`}
        className="grid grid-cols-[2.25rem_minmax(0,1fr)_auto] items-center gap-x-3 rounded-[10px] px-3 py-2.5 outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50"
      >
        <span className="grid size-9 place-items-center rounded-full bg-muted text-foreground/75">
          <Banknote aria-hidden className="size-4" />
        </span>
        <span className="min-w-0">
          <span className="flex min-w-0 items-center gap-2">
            <span className="truncate font-medium">
              {paymentTitle(payment, names, me, t)}
            </span>
            {payment.edited && (
              <Badge variant="outline" className="text-foreground/70">
                {t('transactions.edited')}
              </Badge>
            )}
          </span>
          <span className="block text-[13px] text-muted-foreground">
            {t('groupExpenses.payment.label')}
          </span>
        </span>
        <span className="text-right whitespace-nowrap">
          <span className="block font-medium tabular-nums">
            {formatMoney(payment.amount, language)}
          </span>
          {!involved && (
            <span className="block text-[13px] text-muted-foreground">
              {t('groupExpenses.row.notInvolved')}
            </span>
          )}
        </span>
      </Link>
    </li>
  )
}

function NothingYet({ onAdd }: { onAdd?: (opener: HTMLElement) => void }) {
  const { t } = useTranslation()
  return (
    <div className="flex flex-col items-center gap-1 px-5 pt-8 pb-12 text-center">
      <p className="font-semibold">{t('groupExpenses.empty.title')}</p>
      <p className="max-w-sm text-muted-foreground">
        {onAdd === undefined
          ? t('groupExpenses.empty.archived')
          : t('groupExpenses.empty.text')}
      </p>
      {onAdd !== undefined && (
        <Button
          size="lg"
          className="mt-3"
          onClick={(event) => onAdd(event.currentTarget)}
        >
          <Plus aria-hidden />
          {t('groupExpenses.add')}
        </Button>
      )}
    </div>
  )
}
