import { Banknote, Info, Undo2 } from 'lucide-react'
import { useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useParams } from 'react-router'
import type { Settlement } from '@/api/groupExpenses'
import {
  type SettlementDetail,
  useReverseSettlement,
  useSettlement,
} from '@/api/groupSettlements'
import { type Group, useGroup } from '@/api/groups'
import { useCurrentUser } from '@/api/session'
import { GroupTrail } from '@/components/groups/GroupTrail'
import { LoadFailed, Loading } from '@/components/QueryState'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Sheet, SheetContent, SheetTitle } from '@/components/ui/sheet'
import { formatDay } from '@/lib/dates'
import { apiErrorMessage } from '@/lib/forms'
import { memberNames, paymentTitle } from '@/lib/groupExpenses'
import { formatMoney } from '@/lib/money'
import { cn } from '@/lib/utils'

type Names = ReturnType<typeof memberNames>

/**
 * A payment's detail, from the design canvas, as a panel over the group's page: who paid whom,
 * what it changed in their balances, its trail, and Reverse. It has its own address,
 * `/groups/<id>/payments/<payment id>`, so the back button closes it and a link opens it.
 */
export function GroupPaymentSheet() {
  const { t } = useTranslation()
  const { id = '', settlementId = '' } = useParams()
  const navigate = useNavigate()
  const group = useGroup(id)
  const close = () => void navigate(`/groups/${id}`)

  return (
    <Sheet open onOpenChange={(open) => !open && close()}>
      <SheetContent aria-describedby={undefined} className="gap-0 sm:max-w-110">
        <div className="flex min-h-15 items-center border-b py-3 pr-16 pl-6">
          <p className="truncate text-[13px] font-medium text-muted-foreground">
            {group.data === undefined
              ? t('groupPayments.kickerAlone')
              : t('groupPayments.kicker', { group: group.data.name })}
          </p>
        </div>
        {group.data === undefined ? (
          <div className="px-6">
            <SheetTitle className="sr-only">
              {t('groupPayments.kickerAlone')}
            </SheetTitle>
            {/* The page behind says when the group itself cannot be loaded. */}
            <Loading>{t('groupPayments.loading')}</Loading>
          </div>
        ) : (
          <Detail group={group.data} settlementId={settlementId} />
        )}
      </SheetContent>
    </Sheet>
  )
}

function Detail({
  group,
  settlementId,
}: {
  group: Group
  settlementId: string
}) {
  const { t } = useTranslation()
  const detail = useSettlement(group.id, settlementId)

  if (detail.isPending) {
    return (
      <div className="px-6">
        <SheetTitle className="sr-only">
          {t('groupPayments.kickerAlone')}
        </SheetTitle>
        <Loading>{t('groupPayments.loading')}</Loading>
      </div>
    )
  }
  if (detail.isError) {
    return (
      <div className="px-6 py-4">
        <SheetTitle className="sr-only">
          {t('groupPayments.kickerAlone')}
        </SheetTitle>
        <LoadFailed onRetry={() => void detail.refetch()}>
          {`${t('groupPayments.loadFailed')} ${apiErrorMessage(detail.error, t)}`}
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
  detail: SettlementDetail
  group: Group
}) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  const me = useCurrentUser().data?.id
  const names = memberNames(group.members, me, t)
  const { settlement, trail, currentId } = detail
  const current = !settlement.reversed
  const longDay = formatDay(settlement.occurredOn, language, {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  })
  const [confirming, setConfirming] = useState(false)
  const confirmId = useId()

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-5.5 overflow-y-auto p-6">
      <div className="flex flex-col gap-1">
        <span className="grid size-11 place-items-center rounded-full bg-muted text-foreground/75">
          <Banknote aria-hidden className="size-5" />
        </span>
        <SheetTitle className="mt-3 text-xl leading-snug font-semibold wrap-break-word">
          {paymentTitle(settlement, names, me, t)}
        </SheetTitle>
        <p
          className={cn(
            'text-4xl leading-tight font-semibold tracking-tight tabular-nums',
            !current && 'text-muted-foreground line-through',
          )}
        >
          {formatMoney(settlement.amount, language)}
        </p>
        <p className="mt-0.5 text-muted-foreground">
          {t('groupPayments.meta', {
            date: longDay,
            name: names.objectName(settlement.recordedBy),
          })}
        </p>
        {settlement.edited && (
          <p className="mt-2 flex flex-wrap items-center gap-2 text-[13px] text-foreground/70">
            <Badge variant="outline">{t('transactions.edited')}</Badge>
            {t('groupExpenses.detail.editedBy', {
              name: names.objectName(settlement.recordedBy),
            })}
          </p>
        )}
      </div>

      <Effect settlement={settlement} names={names} me={me} />

      {current && !group.archived && (
        <>
          <Button
            variant="destructive"
            size="lg"
            className="h-10"
            aria-expanded={confirming}
            aria-controls={confirmId}
            onClick={() => setConfirming(!confirming)}
          >
            <Undo2 aria-hidden />
            {t('groupPayments.reverse')}
          </Button>
          <div id={confirmId} className="-mt-2 empty:hidden">
            {confirming && (
              <ConfirmReverse
                group={group}
                settlement={settlement}
                onCancel={() => setConfirming(false)}
                onReversed={() => setConfirming(false)}
              />
            )}
          </div>
        </>
      )}
      {!current && <ReversedNotice detail={detail} groupId={group.id} />}

      <p className="flex items-start gap-2 rounded-[10px] bg-muted px-3 py-2.5 text-[13px] text-foreground/75">
        <Info aria-hidden className="mt-0.5 size-4 flex-none" />
        {t('groupPayments.note')}
      </p>

      <GroupTrail
        trail={trail}
        currentId={currentId}
        names={names}
        describe={(step) =>
          step.action === 'CORRECTED'
            ? t('transactions.trail.corrected', {
                amount: formatMoney(step.amount, language),
              })
            : t('transactions.trail.recorded', {
                amount: formatMoney(step.amount, language),
              })
        }
        note={t('groupPayments.trailNote')}
      />
    </div>
  )
}

/**
 * What the payment changed, as in the design: the payer owes that much less and the receiver is
 * owed that much less. Written as changes: the balances before and after it depend on everything
 * recorded since.
 */
function Effect({
  settlement,
  names,
  me,
}: {
  settlement: Settlement
  names: Names
  me: string | undefined
}) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  const titleId = useId()
  const label = (userId: string) =>
    userId === me
      ? t('groupPayments.effect.yours')
      : t('groupPayments.effect.balanceOf', { name: names.name(userId) })
  const minus = {
    ...settlement.amount,
    amountMinor: -settlement.amount.amountMinor,
  }
  const involved = settlement.from === me || settlement.to === me

  return (
    <section aria-labelledby={titleId} className="flex flex-col gap-2">
      <h3 id={titleId} className="font-semibold">
        {t('groupPayments.effect.title')}
      </h3>
      <dl className="grid grid-cols-[minmax(0,1fr)_auto] gap-x-3 gap-y-2.5 rounded-xl border px-4 py-3.5 tabular-nums">
        <dt className="text-foreground/75">{label(settlement.from)}</dt>
        <dd className="text-right font-semibold text-income-text">
          {formatMoney(settlement.amount, language, { signed: true })}
        </dd>
        <dt className="text-foreground/75">{label(settlement.to)}</dt>
        <dd className="text-right font-semibold">
          {formatMoney(minus, language, { signed: true })}
        </dd>
        {!involved && me !== undefined && (
          <>
            <dt className="text-foreground/75">
              {t('groupPayments.effect.yours')}
            </dt>
            <dd className="text-right text-muted-foreground">
              {t('groupPayments.effect.noChange')}
            </dd>
          </>
        )}
      </dl>
    </section>
  )
}

/** The inline confirmation of the design: a reversal adds an entry, so it is asked first. */
function ConfirmReverse({
  group,
  settlement,
  onCancel,
  onReversed,
}: {
  group: Group
  settlement: Settlement
  onCancel: () => void
  onReversed: () => void
}) {
  const { t } = useTranslation()
  // One key per confirmation: a second tap on "Reverse" reverses the payment once.
  const [idempotencyKey] = useState(() => crypto.randomUUID())
  const reverse = useReverseSettlement(group.id, idempotencyKey)
  const titleId = useId()

  return (
    <div
      role="group"
      aria-labelledby={titleId}
      className="flex flex-col gap-2 rounded-xl border border-destructive/25 bg-destructive/5 px-4 py-3.5 text-destructive"
    >
      <p id={titleId} className="font-semibold">
        {t('groupPayments.confirm.title')}
      </p>
      <p className="text-[13px]">{t('groupPayments.confirm.description')}</p>
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
            reverse.mutate(settlement.id, { onSuccess: onReversed })
          }
        >
          {t('transactions.detail.reverse')}
        </Button>
      </div>
    </div>
  )
}

/** Why a reversed version has no Reverse: only the version in effect can change. */
function ReversedNotice({
  detail,
  groupId,
}: {
  detail: SettlementDetail
  groupId: string
}) {
  const { t } = useTranslation()
  const replaced =
    detail.currentId !== undefined && detail.currentId !== detail.settlement.id
  return (
    <p
      role="status"
      className="flex flex-col gap-1 rounded-[10px] bg-muted px-3 py-2.5 text-[13px] text-foreground/75"
    >
      {replaced ? (
        <>
          {t('groupPayments.oldVersion')}
          <Link
            to={`/groups/${groupId}/payments/${detail.currentId}`}
            replace
            className="font-medium text-foreground underline underline-offset-3"
          >
            {t('transactions.detail.seeCurrent')}
          </Link>
        </>
      ) : (
        t('groupPayments.reversed')
      )}
    </p>
  )
}
