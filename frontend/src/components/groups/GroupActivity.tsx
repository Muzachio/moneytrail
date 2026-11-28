import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { type ActivityItem, useGroupActivity } from '@/api/groupSettlements'
import type { Group, Profile } from '@/api/groups'
import { useCurrentUser } from '@/api/session'
import { PersonAvatar } from '@/components/PersonAvatar'
import { LoadFailed, Loading } from '@/components/QueryState'
import { Button } from '@/components/ui/button'
import { formatWhen } from '@/lib/dates'
import { apiErrorMessage } from '@/lib/forms'
import { formatMoney } from '@/lib/money'
import { cn } from '@/lib/utils'

type People = {
  person: (userId: string) => Profile | undefined
  /** "You", or the person's name, as the subject of a line. */
  name: (userId: string) => string
  /** "you" ("ti" in Portuguese) after a preposition: "a payment to you", "para ti". */
  objectName: (userId: string) => string
}

/**
 * What happened in the group, newest first, as in the design canvas: who did it, what, and when.
 * Each item is written as it was at that moment, so an edited expense keeps its old line.
 */
export function GroupActivity({
  group,
  className,
}: {
  group: Group
  className?: string
}) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  const me = useCurrentUser().data?.id
  const feed = useGroupActivity(group.id)
  const titleId = useId()

  let body
  if (feed.data === undefined) {
    // A failed "Load more" keeps the pages already loaded: only a feed with nothing to show fails.
    body = feed.isError ? (
      <LoadFailed onRetry={() => void feed.refetch()}>
        {t('groupActivity.loadFailed')}
      </LoadFailed>
    ) : (
      <Loading>{t('groupActivity.loading')}</Loading>
    )
  } else {
    const items = feed.data.pages.flatMap((page) => page.items)
    const people = peopleOf(
      [...group.members, ...feed.data.pages.flatMap((page) => page.people)],
      me,
      t,
    )
    body =
      items.length === 0 ? (
        <p className="py-2 text-muted-foreground">{t('groupActivity.empty')}</p>
      ) : (
        <ol>
          {items.map((item) => {
            const what = describe(item, people, me, language, t)
            if (what === undefined) {
              return null
            }
            const actor = people.person(item.actorId)
            return (
              <li
                key={item.id}
                className="grid grid-cols-[1.75rem_minmax(0,1fr)] gap-x-3 border-b border-border/60 py-2.25 last:border-b-0"
              >
                {actor === undefined ? (
                  <span aria-hidden className="size-7" />
                ) : (
                  <PersonAvatar person={actor} className="size-7 text-xs" />
                )}
                <span className="min-w-0">
                  <span className="block wrap-break-word">
                    <span className="font-semibold">
                      {people.name(item.actorId)}
                    </span>{' '}
                    {what}
                  </span>
                  <span className="block text-xs text-muted-foreground">
                    {formatWhen(item.occurredAt, language, {
                      today: t('transactions.today'),
                      yesterday: t('transactions.yesterday'),
                    })}
                  </span>
                </span>
              </li>
            )
          })}
        </ol>
      )
  }

  return (
    <section
      aria-labelledby={titleId}
      className={cn(
        'flex flex-col gap-1.5 rounded-[14px] border bg-card px-5 pt-4.5 pb-5',
        className,
      )}
    >
      <h2 id={titleId} className="text-[15px] font-semibold">
        {t('groupActivity.title')}
      </h2>
      {body}
      {feed.hasNextPage && (
        <Button
          variant="outline"
          size="lg"
          className="mt-1 self-start"
          disabled={feed.isFetchingNextPage}
          onClick={() => void feed.fetchNextPage()}
        >
          {feed.isFetchingNextPage
            ? t('transactions.loadingMore')
            : t('transactions.loadMore')}
        </Button>
      )}
      {feed.isFetchNextPageError && (
        <p role="alert" className="text-[13px] text-destructive">
          {t('groupActivity.loadMoreFailed')} {apiErrorMessage(feed.error, t)}
        </p>
      )}
    </section>
  )
}

/** The people the feed names: the members now, and anyone who left, from the feed's pages. */
function peopleOf(
  profiles: readonly Profile[],
  me: string | undefined,
  t: TFunction,
): People {
  const byId = new Map(profiles.map((profile) => [profile.userId, profile]))
  const known = (userId: string) =>
    byId.get(userId)?.name ?? t('groupExpenses.formerMember')
  return {
    person: (userId) => byId.get(userId),
    name: (userId) => (userId === me ? t('group.you') : known(userId)),
    objectName: (userId) =>
      userId === me ? t('groupExpenses.youObject') : known(userId),
  }
}

/**
 * The rest of an item's line after who did it: "added Dinner, €84.00". The verb agrees with "you"
 * in Portuguese ("adicionaste"), so each line has a form for the signed-in person. Undefined for a
 * kind this version of the app does not know yet, which it leaves out.
 */
function describe(
  item: ActivityItem,
  people: People,
  me: string | undefined,
  language: string,
  t: TFunction,
): string | undefined {
  const form = item.actorId === me ? 'you' : 'other'
  const money = (amount: ActivityItem['amount']) =>
    amount === undefined ? '' : formatMoney(amount, language)
  const values = {
    description: item.description ?? '',
    amount: money(item.amount),
    previousAmount: money(item.previousAmount),
    from: item.from === undefined ? '' : people.objectName(item.from),
    to: item.to === undefined ? '' : people.objectName(item.to),
  }
  switch (item.kind) {
    case 'GROUP_CREATED':
    case 'MEMBER_JOINED':
    case 'MEMBER_LEFT':
    case 'EXPENSE_ADDED':
    case 'EXPENSE_REVERSED':
    case 'SETTLEMENT_EDITED':
    case 'SETTLEMENT_REVERSED':
      return t(`groupActivity.items.${item.kind}.${form}`, values)
    case 'EXPENSE_EDITED':
      return item.previousAmount !== undefined &&
        item.amount !== undefined &&
        item.previousAmount.amountMinor !== item.amount.amountMinor
        ? t(`groupActivity.items.EXPENSE_EDITED_AMOUNT.${form}`, values)
        : t(`groupActivity.items.EXPENSE_EDITED.${form}`, values)
    case 'SETTLEMENT_RECORDED':
      // "recorded a payment of €20.00 to Rita" when they paid it themselves, as in the design.
      return item.from === item.actorId
        ? t(`groupActivity.items.SETTLEMENT_RECORDED.${form}`, values)
        : t(`groupActivity.items.SETTLEMENT_RECORDED_BETWEEN.${form}`, values)
    default:
      return undefined
  }
}
