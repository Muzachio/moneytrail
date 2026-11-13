import { Check, Copy, Pencil, Undo2 } from 'lucide-react'
import { useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useLocation, useNavigate, useParams } from 'react-router'
import type { Account } from '@/api/accounts'
import type { Category } from '@/api/categories'
import {
  type TrailStep,
  type Transaction,
  type TransactionDetail,
  useReverseTransaction,
  useTransaction,
} from '@/api/transactions'
import { LoadFailed, Loading } from '@/components/QueryState'
import { TransactionDialog } from '@/components/transactions/TransactionDialog'
import { useLedgerNames } from '@/components/transactions/useLedgerNames'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Sheet, SheetContent, SheetTitle } from '@/components/ui/sheet'
import { isLiability } from '@/lib/accounts'
import { formatDateTime, formatDay, formatShortDateTime } from '@/lib/dates'
import { apiErrorMessage } from '@/lib/forms'
import { formatAmount, formatMoney, sumMoney } from '@/lib/money'
import { formatTransactionAmount, shortId } from '@/lib/transactions'
import { cn } from '@/lib/utils'

/**
 * The transaction detail of the design canvas, as a panel over the list: its details, the ledger
 * postings it was recorded as, and its trail. It has its own address, `/transactions/<id>`, so the
 * back button closes it and a link opens it.
 */
export function TransactionDetailSheet() {
  const { t } = useTranslation()
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const { search } = useLocation()
  const close = () => void navigate({ pathname: '/transactions', search })

  return (
    <Sheet open onOpenChange={(open) => !open && close()}>
      <SheetContent aria-describedby={undefined} className="gap-0 sm:max-w-110">
        <div className="flex min-h-15 items-center border-b py-3 pr-16 pl-6">
          <p className="text-[13px] font-medium text-muted-foreground">
            {t('transactions.detail.kicker')}
          </p>
        </div>
        <Detail id={id} />
      </SheetContent>
    </Sheet>
  )
}

function Detail({ id }: { id: string }) {
  const { t } = useTranslation()
  const detail = useTransaction(id)
  const names = useLedgerNames()

  if (detail.isPending || names.isPending) {
    return (
      <div className="px-6">
        {/* A dialog needs a title, also while its content is on its way. */}
        <SheetTitle className="sr-only">
          {t('transactions.detail.kicker')}
        </SheetTitle>
        <Loading>{t('transactions.detail.loading')}</Loading>
      </div>
    )
  }
  if (detail.isError || names.isError) {
    return (
      <div className="px-6 py-4">
        <SheetTitle className="sr-only">
          {t('transactions.detail.kicker')}
        </SheetTitle>
        <LoadFailed
          onRetry={() => {
            void detail.refetch()
            names.refetch()
          }}
        >
          {detail.isError
            ? `${t('transactions.detail.loadFailed')} ${apiErrorMessage(detail.error, t)}`
            : t('transactions.detail.loadFailed')}
        </LoadFailed>
      </div>
    )
  }
  return <DetailContent detail={detail.data} names={names} />
}

type Names = ReturnType<typeof useLedgerNames>

function DetailContent({
  detail,
  names,
}: {
  detail: TransactionDetail
  names: Names
}) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  const navigate = useNavigate()
  const { search } = useLocation()
  const { transaction, trail, currentId } = detail
  const current = !transaction.reversed
  const labels = names.labels(transaction)
  const Icon = labels.icon
  const longDay = formatDay(transaction.occurredOn, language, {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  })

  const [editing, setEditing] = useState(false)
  const [editButton, setEditButton] = useState<HTMLButtonElement | null>(null)
  const [confirming, setConfirming] = useState(false)
  const confirmId = useId()

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-5.5 overflow-y-auto p-6">
      <div className="flex flex-col gap-1">
        <span className="grid size-11 place-items-center rounded-full bg-muted text-foreground/75">
          <Icon aria-hidden className="size-5" />
        </span>
        <SheetTitle className="mt-3 text-xl leading-snug font-semibold wrap-break-word">
          {transaction.description}
        </SheetTitle>
        <p
          className={cn(
            'text-4xl leading-tight font-semibold tracking-tight tabular-nums',
            transaction.type === 'INCOME' && 'text-income-text',
            !current && 'text-muted-foreground line-through',
          )}
        >
          {formatTransactionAmount(transaction, language)}
        </p>
        <p className="mt-0.5 text-muted-foreground">
          {longDay} · {labels.account}
        </p>
        {transaction.edited && (
          <p className="mt-2 flex flex-wrap items-center gap-2 text-[13px] text-foreground/70">
            <Badge variant="outline">{t('transactions.edited')}</Badge>
            {correctedFrom(transaction, trail, language, t)}
          </p>
        )}
      </div>

      {current ? (
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
                transaction={transaction}
                onCancel={() => setConfirming(false)}
                onReversed={() => setConfirming(false)}
              />
            )}
          </div>
        </>
      ) : (
        <ReversedNotice detail={detail} />
      )}

      <Details transaction={transaction} names={names} longDay={longDay} />
      <Ledger detail={detail} names={names} />
      <Trail trail={trail} currentId={currentId} />

      {current && (
        <TransactionDialog
          open={editing}
          onOpenChange={setEditing}
          editing={transaction}
          onSaved={(saved) =>
            // The correction is a new entry with its own id: the panel follows it.
            void navigate(
              { pathname: `/transactions/${saved.id}`, search },
              { replace: true },
            )
          }
          returnFocusTo={editButton}
        />
      )}
    </div>
  )
}

/** "Corrected from €25.70", from the version this one replaced. */
function correctedFrom(
  transaction: Transaction,
  trail: TrailStep[],
  language: string,
  t: ReturnType<typeof useTranslation>['t'],
) {
  const at = trail.findIndex((step) => step.entryId === transaction.id)
  const previous = trail
    .slice(0, Math.max(at, 0))
    .findLast((step) => step.action !== 'REVERSED')
  if (
    previous === undefined ||
    previous.amount.amountMinor === transaction.amount.amountMinor
  ) {
    return t('transactions.detail.correctedSameAmount')
  }
  return t('transactions.detail.correctedFrom', {
    amount: formatMoney(previous.amount, language),
  })
}

/** The inline confirmation of the design canvas: a reversal adds an entry, so it is asked first. */
function ConfirmReverse({
  transaction,
  onCancel,
  onReversed,
}: {
  transaction: Transaction
  onCancel: () => void
  onReversed: () => void
}) {
  const { t } = useTranslation()
  // One key per confirmation: a second tap on "Reverse transaction" reverses it once.
  const [idempotencyKey] = useState(() => crypto.randomUUID())
  const reverse = useReverseTransaction(idempotencyKey)
  const titleId = useId()

  return (
    <div
      role="group"
      aria-labelledby={titleId}
      className="flex flex-col gap-2 rounded-xl border border-destructive/25 bg-destructive/5 px-4 py-3.5 text-destructive"
    >
      <p id={titleId} className="font-semibold">
        {t('transactions.detail.confirm.title')}
      </p>
      <p className="text-[13px]">
        {t('transactions.detail.confirm.description')}
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
            reverse.mutate({ id: transaction.id }, { onSuccess: onReversed })
          }
        >
          {t('transactions.detail.confirm.reverse')}
        </Button>
      </div>
    </div>
  )
}

/** Why a reversed version has no Edit or Reverse: only the version in effect can change. */
function ReversedNotice({ detail }: { detail: TransactionDetail }) {
  const { t } = useTranslation()
  const { search } = useLocation()
  const replaced =
    detail.currentId !== undefined && detail.currentId !== detail.transaction.id
  return (
    <p
      role="status"
      className="flex flex-col gap-1 rounded-[10px] bg-muted px-3 py-2.5 text-[13px] text-foreground/75"
    >
      {replaced ? (
        <>
          {t('transactions.detail.oldVersion')}
          <Link
            to={{ pathname: `/transactions/${detail.currentId}`, search }}
            replace
            className="font-medium text-foreground underline underline-offset-3"
          >
            {t('transactions.detail.seeCurrent')}
          </Link>
        </>
      ) : (
        t('transactions.detail.reversed')
      )}
    </p>
  )
}

function Details({
  transaction,
  names,
  longDay,
}: {
  transaction: Transaction
  names: Names
  longDay: string
}) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  const titleId = useId()
  const rows: [string, string][] =
    transaction.type === 'TRANSFER'
      ? [
          [
            t('transactions.detail.from'),
            names.accountName(transaction.fromAccountId),
          ],
          [
            t('transactions.detail.to'),
            names.accountName(transaction.toAccountId),
          ],
        ]
      : [
          [
            t('transactions.detail.category'),
            names.categoryName(transaction.categoryId),
          ],
          [
            t('transactions.detail.account'),
            names.accountName(transaction.accountId),
          ],
        ]
  rows.push(
    [t('transactions.detail.date'), longDay],
    [
      t('transactions.detail.recorded'),
      formatDateTime(transaction.recordedAt, language),
    ],
  )
  return (
    <section aria-labelledby={titleId} className="flex flex-col gap-2">
      <h3 id={titleId} className="font-semibold">
        {t('transactions.detail.details')}
      </h3>
      <dl className="grid grid-cols-[6.5rem_minmax(0,1fr)] gap-x-3 gap-y-2.5 rounded-xl border px-4 py-3.5">
        {rows.map(([term, value]) => (
          <div key={term} className="contents">
            <dt className="text-muted-foreground">{term}</dt>
            <dd className="font-medium wrap-break-word">{value}</dd>
          </div>
        ))}
      </dl>
    </section>
  )
}

/** "Expense:Food", "Liability:Credit card": the ledger's name of what a posting moved. */
function ledgerAccount(
  id: string,
  names: Names,
  t: ReturnType<typeof useTranslation>['t'],
) {
  const category: Category | undefined = names.category(id)
  if (category !== undefined) {
    return t(`transactions.detail.ledgerAccount.${category.kind}`, {
      name: names.categoryName(id),
    })
  }
  const account: Account | undefined = names.account(id)
  if (account !== undefined) {
    return t(
      isLiability(account.type)
        ? 'transactions.detail.ledgerAccount.LIABILITY'
        : 'transactions.detail.ledgerAccount.ASSET',
      { name: account.name },
    )
  }
  return shortId(id)
}

function Ledger({
  detail,
  names,
}: {
  detail: TransactionDetail
  names: Names
}) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  const titleId = useId()
  const currency = detail.transaction.amount.currency
  const total = sumMoney(
    currency,
    detail.postings.map((posting) => posting.amount),
  )
  const [copied, setCopied] = useState(false)

  return (
    <section aria-labelledby={titleId} className="flex flex-col gap-2">
      <div className="flex items-center justify-between gap-3">
        <h3 id={titleId} className="font-semibold">
          {t('transactions.detail.ledger')}
        </h3>
        {/* The API refuses any entry that does not balance; the badge shows it for this one. */}
        {total.amountMinor === 0 && (
          <span className="inline-flex h-5.5 items-center gap-1 rounded-full bg-income/10 px-2 text-xs font-medium text-income-text">
            <Check aria-hidden className="size-3" strokeWidth={2.5} />
            {t('transactions.detail.balanced')}
          </span>
        )}
      </div>
      <p className="text-[13px] text-muted-foreground">
        {t('transactions.detail.ledgerHelp')}
      </p>
      <div className="overflow-hidden rounded-xl border">
        <table className="w-full border-collapse font-mono text-[13px] tabular-nums">
          <thead>
            <tr className="bg-muted/50 font-sans text-xs text-muted-foreground">
              <th
                scope="col"
                className="border-b px-3.5 py-2 text-left font-medium"
              >
                {t('transactions.detail.postingAccount')}
              </th>
              <th
                scope="col"
                className="border-b px-3.5 py-2 text-right font-medium"
              >
                {t('transactions.detail.postingAmount', { currency })}
              </th>
            </tr>
          </thead>
          <tbody>
            {detail.postings.map((posting) => (
              <tr
                key={posting.accountId}
                className="border-b border-border/60 last:border-border"
              >
                <td className="px-3.5 py-2.5 wrap-break-word">
                  {ledgerAccount(posting.accountId, names, t)}
                </td>
                <td className="px-3.5 py-2.5 text-right whitespace-nowrap">
                  {formatAmount(posting.amount, language, { signed: true })}
                </td>
              </tr>
            ))}
            <tr>
              <th
                scope="row"
                className="px-3.5 py-2.5 text-left font-sans font-semibold"
              >
                {t('transactions.detail.total')}
              </th>
              <td className="px-3.5 py-2.5 text-right font-medium">
                {formatAmount(total, language)}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div className="flex items-center justify-between gap-2 text-xs text-muted-foreground">
        <span className="min-w-0 break-all">
          {t('transactions.detail.entry')}{' '}
          <span className="font-mono text-foreground/70">
            {detail.transaction.id}
          </span>
        </span>
        <Button
          variant="ghost"
          size="icon"
          aria-label={t('transactions.detail.copyEntry')}
          className="flex-none text-foreground/70"
          onClick={() => {
            void navigator.clipboard
              ?.writeText(detail.transaction.id)
              .then(() => setCopied(true))
          }}
        >
          {copied ? <Check aria-hidden /> : <Copy aria-hidden />}
        </Button>
        <span role="status" className="sr-only">
          {copied ? t('transactions.detail.copied') : ''}
        </span>
      </div>
    </section>
  )
}

/** Every version and every reversal, newest first, as in the design canvas. */
function Trail({
  trail,
  currentId,
}: {
  trail: TrailStep[]
  currentId: string | undefined
}) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  const titleId = useId()
  const first = trail[0]?.entryId
  const steps = trail.toReversed()

  const what = (step: TrailStep) => {
    const amount = formatMoney(step.amount, language)
    switch (step.action) {
      case 'RECORDED':
        return t('transactions.trail.recorded', { amount })
      case 'CORRECTED':
        return t('transactions.trail.corrected', { amount })
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
          const when = formatShortDateTime(step.recordedAt, language)
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
                  {step.reversesEntryId === undefined
                    ? t('transactions.trail.entry', {
                        when,
                        entry: shortId(step.entryId),
                      })
                    : t('transactions.trail.entryCancels', {
                        when,
                        entry: shortId(step.entryId),
                        cancelled: shortId(step.reversesEntryId),
                      })}
                </p>
              </div>
            </li>
          )
        })}
      </ol>
      <p className="rounded-[10px] bg-muted px-3 py-2.5 text-[13px] text-foreground/70">
        {t('transactions.trail.note')}
      </p>
    </section>
  )
}
