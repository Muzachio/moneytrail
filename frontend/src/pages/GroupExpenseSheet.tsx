import { Pencil, Undo2 } from 'lucide-react'
import { useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useParams } from 'react-router'
import {
  type GroupExpense,
  type GroupExpenseDetail,
  type GroupTrailStep,
  useGroupExpense,
  useReverseGroupExpense,
} from '@/api/groupExpenses'
import { type Group, useGroup } from '@/api/groups'
import { useCurrentUser } from '@/api/session'
import { GroupCategoryIcon } from '@/components/groups/GroupCategoryIcon'
import { GroupExpenseDialog } from '@/components/groups/GroupExpenseDialog'
import { LoadFailed, Loading } from '@/components/QueryState'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Sheet, SheetContent, SheetTitle } from '@/components/ui/sheet'
import { formatDay, formatShortDateTime } from '@/lib/dates'
import { apiErrorMessage } from '@/lib/forms'
import { groupCategoryName, memberNames } from '@/lib/groupExpenses'
import { formatMoney } from '@/lib/money'
import { formatBasisPoints, roundedUp } from '@/lib/splits'
import { cn } from '@/lib/utils'

type Names = ReturnType<typeof memberNames>

/**
 * A group expense's detail, from the design canvas, as a panel over the group's page: what it was,
 * who paid, how it is split and its trail. It has its own address,
 * `/groups/<id>/expenses/<expense id>`, so the back button closes it and a link opens it.
 */
export function GroupExpenseSheet() {
  const { t } = useTranslation()
  const { id = '', expenseId = '' } = useParams()
  const navigate = useNavigate()
  const group = useGroup(id)
  const close = () => void navigate(`/groups/${id}`)

  return (
    <Sheet open onOpenChange={(open) => !open && close()}>
      <SheetContent aria-describedby={undefined} className="gap-0 sm:max-w-110">
        <div className="flex min-h-15 items-center border-b py-3 pr-16 pl-6">
          <p className="truncate text-[13px] font-medium text-muted-foreground">
            {group.data === undefined
              ? t('groupExpenses.detail.kickerAlone')
              : t('groupExpenses.detail.kicker', { group: group.data.name })}
          </p>
        </div>
        {group.data === undefined ? (
          <div className="px-6">
            <SheetTitle className="sr-only">
              {t('groupExpenses.detail.kickerAlone')}
            </SheetTitle>
            {/* The page behind says when the group itself cannot be loaded. */}
            <Loading>{t('groupExpenses.detail.loading')}</Loading>
          </div>
        ) : (
          <Detail group={group.data} expenseId={expenseId} />
        )}
      </SheetContent>
    </Sheet>
  )
}

function Detail({ group, expenseId }: { group: Group; expenseId: string }) {
  const { t } = useTranslation()
  const detail = useGroupExpense(group.id, expenseId)

  if (detail.isPending) {
    return (
      <div className="px-6">
        <SheetTitle className="sr-only">
          {t('groupExpenses.detail.kickerAlone')}
        </SheetTitle>
        <Loading>{t('groupExpenses.detail.loading')}</Loading>
      </div>
    )
  }
  if (detail.isError) {
    return (
      <div className="px-6 py-4">
        <SheetTitle className="sr-only">
          {t('groupExpenses.detail.kickerAlone')}
        </SheetTitle>
        <LoadFailed onRetry={() => void detail.refetch()}>
          {`${t('groupExpenses.detail.loadFailed')} ${apiErrorMessage(detail.error, t)}`}
        </LoadFailed>
      </div>
    )
  }
  return <DetailContent detail={detail.data} group={group} />
}

function DetailContent({
  detail,
  group,
}: {
  detail: GroupExpenseDetail
  group: Group
}) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  const navigate = useNavigate()
  const me = useCurrentUser().data?.id
  const names = memberNames(group.members, me, t)
  const { expense, trail, currentId } = detail
  const current = !expense.reversed
  const longDay = formatDay(expense.occurredOn, language, {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  })
  const payer =
    expense.paidBy === me
      ? t('groupExpenses.row.youPaid')
      : t('groupExpenses.row.paid', { name: names.name(expense.paidBy) })
  const mine = expense.split.members.find((member) => member.userId === me)

  const [editing, setEditing] = useState(false)
  const [editButton, setEditButton] = useState<HTMLButtonElement | null>(null)
  const [confirming, setConfirming] = useState(false)
  const confirmId = useId()

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-5.5 overflow-y-auto p-6">
      <div className="flex flex-col gap-1">
        <span className="grid size-11 place-items-center rounded-full bg-muted text-foreground/75">
          <GroupCategoryIcon
            category={expense.category}
            aria-hidden
            className="size-5"
          />
        </span>
        <SheetTitle className="mt-3 text-xl leading-snug font-semibold wrap-break-word">
          {expense.description}
        </SheetTitle>
        <p
          className={cn(
            'text-4xl leading-tight font-semibold tracking-tight tabular-nums',
            !current && 'text-muted-foreground line-through',
          )}
        >
          {formatMoney(expense.amount, language)}
        </p>
        <p className="mt-0.5 text-muted-foreground">
          {groupCategoryName(expense.category, t)} · {payer} · {longDay}
        </p>
        {expense.edited && (
          <p className="mt-2 flex flex-wrap items-center gap-2 text-[13px] text-foreground/70">
            <Badge variant="outline">{t('transactions.edited')}</Badge>
            {t('groupExpenses.detail.editedBy', {
              name: names.objectName(expense.recordedBy),
            })}
          </p>
        )}
      </div>

      <div className="flex items-center justify-between gap-3 rounded-xl border bg-muted/40 px-4 py-3.5">
        <span className="text-foreground/75">
          {t('groupExpenses.detail.yourShare')}
        </span>
        <span className="text-xl font-semibold tabular-nums">
          {mine === undefined
            ? t('groupExpenses.row.notInvolved')
            : formatMoney(mine.share, language)}
        </span>
      </div>

      {current && !group.archived && (
        <>
          <div className="grid grid-cols-2 gap-2">
            <Button
              variant="outline"
              size="lg"
              className="h-10"
              onClick={(event) => {
                setEditButton(event.currentTarget)
                setEditing(true)
              }}
            >
              <Pencil aria-hidden />
              {t('transactions.detail.edit')}
            </Button>
            <Button
              variant="destructive"
              size="lg"
              className="h-10"
              aria-expanded={confirming}
              aria-controls={confirmId}
              onClick={() => setConfirming(!confirming)}
            >
              <Undo2 aria-hidden />
              {t('transactions.detail.reverse')}
            </Button>
          </div>
          <div id={confirmId} className="-mt-2 empty:hidden">
            {confirming && (
              <ConfirmReverse
                group={group}
                expense={expense}
                onCancel={() => setConfirming(false)}
                onReversed={() => setConfirming(false)}
              />
            )}
          </div>
        </>
      )}
      {!current && <ReversedNotice detail={detail} groupId={group.id} />}

      <SplitSection expense={expense} names={names} me={me} />
      <Trail trail={trail} currentId={currentId} names={names} />

      {current && !group.archived && (
        <GroupExpenseDialog
          open={editing}
          onOpenChange={setEditing}
          group={group}
          editing={expense}
          onSaved={(saved) =>
            // The correction is a new entry with its own id: the panel follows it.
            void navigate(`/groups/${group.id}/expenses/${saved.id}`, {
              replace: true,
            })
          }
          returnFocusTo={editButton}
        />
      )}
    </div>
  )
}

/** The inline confirmation of the design canvas: a reversal adds an entry, so it is asked first. */
function ConfirmReverse({
  group,
  expense,
  onCancel,
  onReversed,
}: {
  group: Group
  expense: GroupExpense
  onCancel: () => void
  onReversed: () => void
}) {
  const { t } = useTranslation()
  // One key per confirmation: a second tap on "Reverse" reverses the expense once.
  const [idempotencyKey] = useState(() => crypto.randomUUID())
  const reverse = useReverseGroupExpense(group.id, idempotencyKey)
  const titleId = useId()

  return (
    <div
      role="group"
      aria-labelledby={titleId}
      className="flex flex-col gap-2 rounded-xl border border-destructive/25 bg-destructive/5 px-4 py-3.5 text-destructive"
    >
      <p id={titleId} className="font-semibold">
        {t('groupExpenses.detail.confirm.title')}
      </p>
      <p className="text-[13px]">
        {t('groupExpenses.detail.confirm.description')}
      </p>
      {reverse.isError && (
        <p role="alert" className="text-[13px] font-medium">
          {apiErrorMessage(reverse.error, t)}
        </p>
      )}
      <div className="flex flex-wrap justify-end gap-2 pt-1">
        <Button
          variant="outline"
          size="lg"
          className="text-foreground"
          onClick={onCancel}
        >
          {t('transactions.detail.confirm.keep')}
        </Button>
        <Button
          size="lg"
          disabled={reverse.isPending}
          className="bg-destructive text-white hover:bg-destructive/90"
          onClick={() =>
            reverse.mutate({ expenseId: expense.id }, { onSuccess: onReversed })
          }
        >
          {t('groupExpenses.detail.confirm.reverse')}
        </Button>
      </div>
    </div>
  )
}

/** Why a reversed version has no Edit or Reverse: only the version in effect can change. */
function ReversedNotice({
  detail,
  groupId,
}: {
  detail: GroupExpenseDetail
  groupId: string
}) {
  const { t } = useTranslation()
  const replaced =
    detail.currentId !== undefined && detail.currentId !== detail.expense.id
  return (
    <p
      role="status"
      className="flex flex-col gap-1 rounded-[10px] bg-muted px-3 py-2.5 text-[13px] text-foreground/75"
    >
      {replaced ? (
        <>
          {t('groupExpenses.detail.oldVersion')}
          <Link
            to={`/groups/${groupId}/expenses/${detail.currentId}`}
            replace
            className="font-medium text-foreground underline underline-offset-3"
          >
            {t('transactions.detail.seeCurrent')}
          </Link>
        </>
      ) : (
        t('groupExpenses.detail.reversed')
      )}
    </p>
  )
}

/** Who takes part, what each was given and what each pays, with the cents left over explained. */
function SplitSection({
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
  const titleId = useId()
  const { method, members } = expense.split
  const currency = expense.amount.currency
  const total = BigInt(expense.amount.amountMinor)
  const given = (value: number) =>
    method === 'PERCENTAGES'
      ? formatBasisPoints(BigInt(value), language)
      : method === 'EXACT_AMOUNTS'
        ? formatMoney({ amountMinor: value, currency }, language)
        : String(value)
  const sumOfValues = members.reduce(
    (sum, member) => sum + BigInt(member.value),
    0n,
  )
  const valuesTotal =
    method === 'PERCENTAGES'
      ? formatBasisPoints(sumOfValues, language)
      : method === 'EXACT_AMOUNTS'
        ? formatMoney({ amountMinor: Number(sumOfValues), currency }, language)
        : String(sumOfValues)
  const extra = roundedUp(
    total,
    method,
    members.map(({ userId, value, share }) => ({
      member: userId,
      value: BigInt(value),
      share: BigInt(share.amountMinor),
    })),
  )
  const listFormat = new Intl.ListFormat(
    language === 'en' ? 'en-IE' : language,
    { style: 'long', type: 'conjunction' },
  )

  return (
    <section aria-labelledby={titleId} className="flex flex-col gap-2">
      <h3 id={titleId} className="font-semibold">
        {t(`groupExpenses.detail.splitTitle.${method}`)}
      </h3>
      <div className="overflow-hidden rounded-xl border">
        <table className="w-full border-collapse tabular-nums">
          <thead>
            <tr className="bg-muted/50 text-xs text-muted-foreground">
              <th
                scope="col"
                className="border-b px-3.5 py-2 text-left font-medium"
              >
                {t('groupExpenses.detail.member')}
              </th>
              {method !== 'EQUALLY' && (
                <th
                  scope="col"
                  className="border-b px-3.5 py-2 text-right font-medium"
                >
                  {t(`groupExpenses.form.inputHeader.${method}`)}
                </th>
              )}
              <th
                scope="col"
                className="border-b px-3.5 py-2 text-right font-medium"
              >
                {t('groupExpenses.form.shareHeader')}
              </th>
            </tr>
          </thead>
          <tbody>
            {members.map((member) => (
              <tr
                key={member.userId}
                className="border-b border-border/60 last:border-border"
              >
                <th
                  scope="row"
                  className="px-3.5 py-2.5 text-left font-medium wrap-break-word"
                >
                  {names.name(member.userId)}
                  {member.userId === expense.paidBy && (
                    <span className="text-xs font-normal text-muted-foreground">
                      {' · '}
                      {t('groupExpenses.detail.paidTag')}
                    </span>
                  )}
                </th>
                {method !== 'EQUALLY' && (
                  <td className="px-3.5 py-2.5 text-right text-foreground/70">
                    {given(member.value)}
                  </td>
                )}
                <td className="px-3.5 py-2.5 text-right whitespace-nowrap">
                  {formatMoney(member.share, language)}
                </td>
              </tr>
            ))}
            <tr>
              <th scope="row" className="px-3.5 py-2.5 text-left font-semibold">
                {t('groupExpenses.form.total')}
              </th>
              {method !== 'EQUALLY' && (
                <td className="px-3.5 py-2.5 text-right text-foreground/70">
                  {valuesTotal}
                </td>
              )}
              <td className="px-3.5 py-2.5 text-right font-semibold whitespace-nowrap">
                {formatMoney(expense.amount, language)}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      {!expense.split.members.some(
        ({ userId }) => userId === expense.paidBy,
      ) && (
        <p className="text-[13px] text-muted-foreground">
          {expense.paidBy === me
            ? t('groupExpenses.detail.payerNotInSplitYou')
            : t('groupExpenses.detail.payerNotInSplit', {
                name: names.name(expense.paidBy),
              })}
        </p>
      )}
      {extra.length > 0 && (
        <p className="text-[13px] text-muted-foreground">
          {t('groupExpenses.detail.rounding', {
            count: extra.length,
            total: formatMoney(expense.amount, language),
            names: listFormat.format(
              extra.map((userId) => names.objectName(userId)),
            ),
          })}
        </p>
      )}
    </section>
  )
}

/** Every version and every reversal, newest first, each with who made it, as in the design. */
function Trail({
  trail,
  currentId,
  names,
}: {
  trail: GroupTrailStep[]
  currentId: string | undefined
  names: Names
}) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  const titleId = useId()
  const first = trail[0]?.entryId
  const steps = trail.toReversed()

  const what = (step: GroupTrailStep) => {
    const amount = formatMoney(step.amount, language)
    const category =
      step.category === undefined ? '' : groupCategoryName(step.category, t)
    switch (step.action) {
      case 'RECORDED':
        return t('groupExpenses.trail.recorded', { amount, category })
      case 'CORRECTED':
        return t('groupExpenses.trail.corrected', { amount, category })
      case 'REVERSED':
        return step.reversesEntryId === first
          ? t('transactions.trail.originalReversed')
          : t('transactions.trail.correctionReversed')
    }
  }

  return (
    <section aria-labelledby={titleId} className="flex flex-col gap-3">
      <h3 id={titleId} className="font-semibold">
        {t('transactions.trail.title')}
      </h3>
      <ol>
        {steps.map((step, index) => {
          const latest = step.entryId === currentId
          return (
            <li
              key={step.entryId}
              className={cn(
                'relative grid grid-cols-[1.25rem_minmax(0,1fr)] gap-x-3',
                index < steps.length - 1 && 'pb-4.5',
              )}
            >
              {index < steps.length - 1 && (
                <span
                  aria-hidden
                  className="absolute top-4.5 bottom-0 left-[9px] w-0.5 bg-border"
                />
              )}
              <span
                aria-hidden
                className={cn(
                  'relative mt-1.25 size-3 justify-self-center rounded-full ring-3 ring-background',
                  latest
                    ? 'bg-primary'
                    : 'border-2 border-foreground/35 bg-background',
                )}
              />
              <div>
                <p className="font-medium">
                  {what(step)}
                  {latest && (
                    <span className="text-xs font-normal text-muted-foreground">
                      {' · '}
                      {t('transactions.trail.current')}
                    </span>
                  )}
                </p>
                <p className="mt-0.5 text-xs text-muted-foreground">
                  {t('groupExpenses.trail.meta', {
                    name: names.name(step.recordedBy),
                    when: formatShortDateTime(step.recordedAt, language),
                  })}
                </p>
              </div>
            </li>
          )
        })}
      </ol>
      <p className="rounded-[10px] bg-muted px-3 py-2.5 text-[13px] text-foreground/70">
        {t('groupExpenses.trail.note')}
      </p>
    </section>
  )
}
