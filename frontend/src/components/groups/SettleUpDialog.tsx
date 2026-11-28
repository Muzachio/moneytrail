import { Check, Info, ShieldCheck } from 'lucide-react'
import { type FormEvent, useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  type MemberBalance,
  type SuggestedPayment,
  useCreateSettlement,
  useGroupBalances,
  useSettleUp,
} from '@/api/groupSettlements'
import type { Group } from '@/api/groups'
import { useCurrentUser } from '@/api/session'
import { PersonAvatar } from '@/components/PersonAvatar'
import { LoadFailed, Loading } from '@/components/QueryState'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Field, FieldError, FieldLabel } from '@/components/ui/field'
import {
  InputGroup,
  InputGroupAddon,
  InputGroupInput,
} from '@/components/ui/input-group'
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select'
import { today } from '@/lib/dates'
import { returnFocus } from '@/lib/focus'
import { apiErrorMessage, moneyInputMessage } from '@/lib/forms'
import { memberNames } from '@/lib/groupExpenses'
import { currencyLayout, formatMoney, parseMoneyInput } from '@/lib/money'

type Names = ReturnType<typeof memberNames>

/**
 * The settle-up dialog of the design canvas: the payments the API suggests, each recorded with one
 * tap, or a payment of any amount between two members. MoneyTrail never moves money: each payment
 * records one made outside the app.
 */
export function SettleUpDialog({
  open,
  onOpenChange,
  group,
  returnFocusTo,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  group: Group
  /** The button that opened the dialog, which gets the focus back when it closes. */
  returnFocusTo?: HTMLElement | null
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        aria-describedby={undefined}
        className="flex max-h-[calc(100svh-2rem)] flex-col gap-0 p-0 sm:max-w-130"
        onCloseAutoFocus={(event) => returnFocus(event, returnFocusTo)}
      >
        {/* Inside the content, so each opening starts with new suggestions and new keys. */}
        <SettleUpContent group={group} />
      </DialogContent>
    </Dialog>
  )
}

/** The suggestions as they were when the dialog opened, with the balances they came from. */
type Snapshot = { payments: SuggestedPayment[]; balances: MemberBalance[] }

function SettleUpContent({ group }: { group: Group }) {
  const { t } = useTranslation()
  const me = useCurrentUser().data?.id
  const names = memberNames(group.members, me, t)
  const settleUp = useSettleUp(group.id)
  const balances = useGroupBalances(group.id)

  // Kept as they were when the dialog opened: recording one refreshes the group, and the API's
  // new suggestions would make the list jump under the person's finger. A recorded one stays,
  // marked as recorded, as in the design.
  const [snapshot, setSnapshot] = useState<Snapshot>()
  const fresh =
    settleUp.isSuccess &&
    !settleUp.isFetching &&
    balances.isSuccess &&
    !balances.isFetching
  if (snapshot === undefined && fresh) {
    setSnapshot({ payments: settleUp.data, balances: balances.data })
  }
  const failed =
    snapshot === undefined && (settleUp.isError || balances.isError)

  return (
    <>
      <DialogHeader className="px-5 pt-5 pb-1 sm:px-6">
        <DialogTitle className="text-lg font-semibold">
          {t('settleUp.title')}
        </DialogTitle>
        {snapshot !== undefined && (
          <p className="text-[13px] text-muted-foreground">
            {snapshot.payments.length === 0
              ? t('settleUp.subtitleSettled', { group: group.name })
              : t('settleUp.subtitle', {
                  group: group.name,
                  count: snapshot.payments.length,
                })}
          </p>
        )}
      </DialogHeader>

      <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto px-5 pt-3 pb-5 sm:px-6">
        {snapshot === undefined ? (
          failed ? (
            <LoadFailed
              onRetry={() => {
                void settleUp.refetch()
                void balances.refetch()
              }}
            >
              {t('settleUp.loadFailed')}
            </LoadFailed>
          ) : (
            <Loading>{t('groupBalances.loading')}</Loading>
          )
        ) : (
          <Suggestions
            group={group}
            snapshot={snapshot}
            names={names}
            me={me}
          />
        )}

        <div
          aria-hidden
          className="flex items-center gap-3 text-xs text-muted-foreground"
        >
          <span className="h-px flex-1 bg-border" />
          {t('settleUp.or')}
          <span className="h-px flex-1 bg-border" />
        </div>

        <OtherPayment
          group={group}
          names={names}
          me={me}
          suggested={snapshot?.payments}
        />

        <p className="flex items-start gap-2 rounded-[10px] bg-muted px-3 py-2.5 text-[13px] text-foreground/75">
          <Info aria-hidden className="mt-0.5 size-4 flex-none" />
          {t('settleUp.note')}
        </p>
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3 border-t px-5 py-4 sm:px-6">
        <span className="inline-flex items-center gap-1.5 text-xs text-muted-foreground">
          <ShieldCheck aria-hidden className="size-3.5" />
          {t('transactions.form.savedOnce')}
        </span>
        <DialogClose asChild>
          <Button size="lg" className="ml-auto h-10 px-4">
            {t('settleUp.done')}
          </Button>
        </DialogClose>
      </div>
    </>
  )
}

function Suggestions({
  group,
  snapshot,
  names,
  me,
}: {
  group: Group
  snapshot: Snapshot
  names: Names
  me: string | undefined
}) {
  const { t } = useTranslation()
  const titleId = useId()
  return (
    <section aria-labelledby={titleId} className="flex flex-col gap-1">
      <h3 id={titleId} className="mb-1 font-semibold">
        {t('settleUp.suggested')}
      </h3>
      {snapshot.payments.length === 0 ? (
        <p className="text-muted-foreground">{t('settleUp.none')}</p>
      ) : (
        <ul className="border-t border-border/60">
          {snapshot.payments.map((payment) => (
            <SuggestionRow
              // The API never suggests two payments between the same two people.
              key={`${payment.from} ${payment.to}`}
              group={group}
              payment={payment}
              balances={snapshot.balances}
              names={names}
              me={me}
            />
          ))}
        </ul>
      )}
    </section>
  )
}

function SuggestionRow({
  group,
  payment,
  balances,
  names,
  me,
}: {
  group: Group
  payment: SuggestedPayment
  balances: MemberBalance[]
  names: Names
  me: string | undefined
}) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  // One key per suggestion for as long as the dialog is open: a double tap, or a retry after a lost
  // answer, records this payment once.
  const [idempotencyKey] = useState(() => crypto.randomUUID())
  const record = useCreateSettlement(group.id, idempotencyKey)
  const errorId = useId()

  const text =
    payment.from === me
      ? t('settleUp.suggestion.youPay', { to: names.name(payment.to) })
      : payment.to === me
        ? t('settleUp.suggestion.paysYou', { from: names.name(payment.from) })
        : t('settleUp.suggestion.pays', {
            from: names.name(payment.from),
            to: names.name(payment.to),
          })
  // Each greedy payment clears the payer's balance or the receiver's; the design names the first.
  const owed = balances.find((balance) => balance.userId === payment.from)
  const clears = owed?.balance.amountMinor === -payment.amount.amountMinor
  const note = clears
    ? payment.from === me
      ? t('settleUp.suggestion.clearsYours')
      : t('settleUp.suggestion.clears', { name: names.name(payment.from) })
    : undefined
  const amount = formatMoney(payment.amount, language)
  const from = names.member(payment.from)
  const to = names.member(payment.to)

  return (
    <li className="border-b border-border/60">
      <div className="grid min-h-15 grid-cols-[3.25rem_minmax(0,1fr)_auto] items-center gap-x-3 py-2 sm:grid-cols-[3.25rem_minmax(0,1fr)_auto_7rem]">
        <span aria-hidden className="flex items-center">
          {from !== undefined && (
            <PersonAvatar
              person={from}
              className="size-7 text-xs ring-2 ring-background"
            />
          )}
          {to !== undefined && (
            <PersonAvatar
              person={to}
              className="-ml-1.5 size-7 text-xs ring-2 ring-background"
            />
          )}
        </span>
        <span className="min-w-0">
          <span className="block font-medium">{text}</span>
          {note !== undefined && (
            <span className="block text-xs text-muted-foreground">{note}</span>
          )}
        </span>
        <span className="font-semibold tabular-nums">{amount}</span>
        <span className="col-span-3 flex justify-end pt-1.5 sm:col-span-1 sm:pt-0">
          {record.isSuccess ? (
            <span
              role="status"
              className="inline-flex h-9 items-center gap-1.5 font-medium text-income-text"
            >
              <Check aria-hidden className="size-4" />
              {t('settleUp.suggestion.recorded')}
            </span>
          ) : (
            <Button
              variant="outline"
              size="lg"
              className="h-9 px-3.5"
              aria-label={t('settleUp.suggestion.recordLabel', {
                text,
                amount,
              })}
              aria-describedby={record.isError ? errorId : undefined}
              disabled={record.isPending}
              onClick={() =>
                record.mutate({
                  from: payment.from,
                  to: payment.to,
                  amount: payment.amount,
                  occurredOn: today(),
                })
              }
            >
              {record.isPending
                ? t('settleUp.suggestion.recording')
                : t('settleUp.suggestion.record')}
            </Button>
          )}
        </span>
      </div>
      {record.isError && (
        <p
          id={errorId}
          role="alert"
          className="pb-2.5 text-[13px] text-destructive"
        >
          {apiErrorMessage(record.error, t)}
        </p>
      )}
    </li>
  )
}

/**
 * A payment that is not one of the suggestions: from whom, to whom and how much, any amount above
 * zero, paid today. Each payment keeps its key until it is recorded, then the form starts again
 * with a new one.
 */
function OtherPayment({
  group,
  names,
  me,
  suggested,
}: {
  group: Group
  names: Names
  me: string | undefined
  suggested: SuggestedPayment[] | undefined
}) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  const currency = group.currency
  const ids = {
    title: useId(),
    from: useId(),
    to: useId(),
    people: useId(),
    amount: useId(),
    amountError: useId(),
    status: useId(),
  }
  // The signed-in person first, then the others in the order they joined, as in the other dialogs.
  const members = [
    ...group.members.filter((member) => member.userId === me),
    ...group.members.filter((member) => member.userId !== me),
  ]
  const firstOther = members.find((member) => member.userId !== me)?.userId
  const myPayee = suggested?.find((payment) => payment.from === me)?.to

  const [from, setFrom] = useState(me ?? members[0]?.userId ?? '')
  const [to, setTo] = useState<string>()
  const payee = to ?? myPayee ?? firstOther ?? ''
  const [amountText, setAmountText] = useState('')
  const [checked, setChecked] = useState(false)
  const [idempotencyKey, setIdempotencyKey] = useState(() =>
    crypto.randomUUID(),
  )
  const record = useCreateSettlement(group.id, idempotencyKey)
  const [recorded, setRecorded] = useState(false)

  const parsed = parseMoneyInput(amountText, currency)
  const amountError = !checked
    ? undefined
    : parsed.ok
      ? parsed.amountMinor === 0
        ? t('transactions.form.amountZero')
        : undefined
      : parsed.error === 'empty'
        ? t('transactions.form.amountRequired')
        : moneyInputMessage(parsed.error, currency)
  const samePerson = checked && from === payee

  const submit = (event: FormEvent) => {
    event.preventDefault()
    setChecked(true)
    setRecorded(false)
    if (!parsed.ok || parsed.amountMinor === 0 || from === payee) {
      return
    }
    record.mutate(
      {
        from,
        to: payee,
        amount: { amountMinor: parsed.amountMinor, currency },
        occurredOn: today(),
      },
      {
        onSuccess: () => {
          // A new payment from here on: a new key, and the form as it started.
          setIdempotencyKey(crypto.randomUUID())
          setAmountText('')
          setChecked(false)
          setRecorded(true)
          record.reset()
        },
      },
    )
  }

  const { symbol, symbolFirst, zero } = currencyLayout(currency, language)
  const option = (userId: string) =>
    userId === me
      ? t('group.youWithName', { name: names.member(userId)?.name ?? '' })
      : names.name(userId)

  return (
    <section aria-labelledby={ids.title}>
      <form onSubmit={submit} noValidate className="flex flex-col gap-2.5">
        <h3 id={ids.title} className="font-semibold">
          {t('settleUp.other.title')}
        </h3>
        <div className="grid grid-cols-2 gap-2.5 sm:grid-cols-3">
          <Field data-invalid={samePerson} className="min-w-0 gap-1.5">
            <FieldLabel htmlFor={ids.from}>
              {t('settleUp.other.from')}
            </FieldLabel>
            <NativeSelect
              id={ids.from}
              value={from}
              onChange={(event) => setFrom(event.target.value)}
              aria-invalid={samePerson}
              aria-describedby={samePerson ? ids.people : undefined}
              className="h-10 w-full"
            >
              {members.map((member) => (
                <NativeSelectOption key={member.userId} value={member.userId}>
                  {option(member.userId)}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </Field>
          <Field data-invalid={samePerson} className="min-w-0 gap-1.5">
            <FieldLabel htmlFor={ids.to}>{t('settleUp.other.to')}</FieldLabel>
            <NativeSelect
              id={ids.to}
              value={payee}
              onChange={(event) => setTo(event.target.value)}
              aria-invalid={samePerson}
              aria-describedby={samePerson ? ids.people : undefined}
              className="h-10 w-full"
            >
              {members.map((member) => (
                <NativeSelectOption key={member.userId} value={member.userId}>
                  {option(member.userId)}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </Field>
          <Field
            data-invalid={amountError !== undefined}
            className="col-span-2 min-w-0 gap-1.5 sm:col-span-1"
          >
            <FieldLabel htmlFor={ids.amount}>
              {t('settleUp.other.amount')}
            </FieldLabel>
            <InputGroup className="h-10">
              <InputGroupInput
                id={ids.amount}
                type="text"
                inputMode="decimal"
                autoComplete="off"
                spellCheck={false}
                placeholder={zero}
                value={amountText}
                onChange={(event) => setAmountText(event.target.value)}
                aria-invalid={amountError !== undefined}
                aria-describedby={
                  amountError === undefined ? undefined : ids.amountError
                }
                className="tabular-nums"
              />
              <InputGroupAddon
                align={symbolFirst ? 'inline-start' : 'inline-end'}
                aria-hidden
              >
                {symbol}
              </InputGroupAddon>
            </InputGroup>
            <FieldError
              id={ids.amountError}
              errors={
                amountError === undefined ? [] : [{ message: amountError }]
              }
            />
          </Field>
        </div>
        {samePerson && (
          <p id={ids.people} className="text-sm text-destructive">
            {t('settleUp.other.samePerson')}
          </p>
        )}
        <Button
          type="submit"
          variant="outline"
          size="lg"
          className="h-10"
          disabled={record.isPending}
        >
          {t('settleUp.other.submit')}
        </Button>
        <p id={ids.status} role="status" className="text-sm empty:hidden">
          {recorded && (
            <span className="inline-flex items-center gap-1.5 font-medium text-income-text">
              <Check aria-hidden className="size-4" />
              {t('settleUp.other.recorded')}
            </span>
          )}
        </p>
        {record.isError && (
          <p role="alert" className="text-sm text-destructive">
            {apiErrorMessage(record.error, t)}
          </p>
        )}
      </form>
    </section>
  )
}
