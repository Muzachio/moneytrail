import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { CircleAlert, CircleCheck, Info, ShieldCheck } from 'lucide-react'
import { useId, useState } from 'react'
import { Controller, useForm, useWatch } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { z } from 'zod'
import {
  type GroupExpense,
  type NewGroupExpense,
  useCreateGroupExpense,
  useReverseGroupExpense,
} from '@/api/groupExpenses'
import { type Group, groupKey } from '@/api/groups'
import { ApiError } from '@/api/problem'
import { useCurrentUser } from '@/api/session'
import { FormError } from '@/components/FormError'
import { GroupCategoryIcon } from '@/components/groups/GroupCategoryIcon'
import { PersonAvatar } from '@/components/PersonAvatar'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  Field,
  FieldError,
  FieldLabel,
  FieldLegend,
  FieldSet,
} from '@/components/ui/field'
import { Input } from '@/components/ui/input'
import {
  InputGroup,
  InputGroupAddon,
  InputGroupInput,
} from '@/components/ui/input-group'
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select'
import { isDay, today } from '@/lib/dates'
import { returnFocus } from '@/lib/focus'
import { apiErrorMessage, moneyInputMessage } from '@/lib/forms'
import {
  asNewGroupExpense,
  groupCategoryName,
  groupExpenseCategories,
  sameGroupExpense,
} from '@/lib/groupExpenses'
import {
  currencyLayout,
  formatMoney,
  moneyInputText,
  parseMoneyInput,
} from '@/lib/money'
import {
  formatBasisPoints,
  percentInputText,
  type SplitInputTotal,
  type SplitMethod,
  splitMethods,
  type SplitPreview,
  splitPreview,
} from '@/lib/splits'
import { cn } from '@/lib/utils'
import { i18n } from '@/i18n/i18n'

/** `TransactionDescription` in the spec allows 1 to 200 characters. */
const DESCRIPTION_MAX_LENGTH = 200

type GroupExpenseDialogProps = {
  open: boolean
  onOpenChange: (open: boolean) => void
  group: Group
  /** The expense to edit, which fills the form in; a new expense when absent. */
  editing?: GroupExpense
  /** Gets what was saved: the new expense, or the correction that replaced `editing`. */
  onSaved?: (saved: GroupExpense) => void
  /** The button that opened the dialog, which gets the focus back when it closes. */
  returnFocusTo?: HTMLElement | null
}

/**
 * The new group expense dialog of the design canvas: who paid, who takes part and how it is split,
 * with each person's share shown as it is typed, worked out as the API will (`splitPreview`).
 * Opened with an expense to edit, it starts filled in and saves the edit as a reversal of the
 * expense plus its correction, in one request.
 */
export function GroupExpenseDialog({
  open,
  onOpenChange,
  group,
  editing,
  onSaved,
  returnFocusTo,
}: GroupExpenseDialogProps) {
  const { t } = useTranslation()
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        aria-describedby={undefined}
        className="flex max-h-[calc(100svh-2rem)] flex-col gap-0 p-0 sm:max-w-140"
        onCloseAutoFocus={(event) => returnFocus(event, returnFocusTo)}
      >
        <DialogHeader className="px-5 pt-5 pb-1 sm:px-6">
          <DialogTitle className="text-lg font-semibold">
            {editing === undefined
              ? t('groupExpenses.form.newTitle')
              : t('groupExpenses.form.editTitle')}
          </DialogTitle>
          <p className="text-[13px] text-muted-foreground">
            {t('groupExpenses.form.subtitle', {
              name: group.name,
              currency: group.currency,
            })}
          </p>
        </DialogHeader>
        {/* Inside the content, so each opening starts a new form and a new Idempotency-Key. */}
        <GroupExpenseForm
          group={group}
          editing={editing}
          onSaved={(saved) => {
            onSaved?.(saved)
            onOpenChange(false)
          }}
        />
      </DialogContent>
    </Dialog>
  )
}

const formSchema = z.object({
  amount: z.string(),
  description: z.string(),
  occurredOn: z.string(),
  paidBy: z.string(),
  category: z.string(),
})

type FormValues = z.input<typeof formSchema>
type FormField = keyof FormValues
/** What the form's own fields hold once checked; the split comes from the table. */
type Checked = Omit<NewGroupExpense, 'split'>

/**
 * The fields besides the split, checked as a whole so that every problem shows at once, with
 * messages looked up as the form is checked, in the language the person is using then.
 */
function checkedSchema(currency: string) {
  return formSchema.transform((values, context): Checked => {
    let valid = true
    const problem = (path: FormField, message: string) => {
      valid = false
      context.addIssue({ code: 'custom', path: [path], message })
    }
    const amount = parseMoneyInput(values.amount, currency)
    if (!amount.ok) {
      problem(
        'amount',
        amount.error === 'empty'
          ? i18n.t('transactions.form.amountRequired')
          : moneyInputMessage(amount.error, currency),
      )
    } else if (amount.amountMinor === 0) {
      problem('amount', i18n.t('transactions.form.amountZero'))
    }
    const description = values.description.trim()
    if (description === '') {
      problem('description', i18n.t('transactions.form.descriptionRequired'))
    } else if (description.length > DESCRIPTION_MAX_LENGTH) {
      problem(
        'description',
        i18n.t('transactions.form.descriptionTooLong', {
          max: DESCRIPTION_MAX_LENGTH,
        }),
      )
    }
    if (!isDay(values.occurredOn)) {
      problem('occurredOn', i18n.t('transactions.form.dateRequired'))
    }
    if (values.paidBy === '') {
      problem('paidBy', i18n.t('groupExpenses.form.pickPayer'))
    }
    if (values.category === '') {
      problem('category', i18n.t('transactions.form.pickCategory'))
    }
    if (!valid || !amount.ok) {
      return z.NEVER
    }
    return {
      amount: { amountMinor: amount.amountMinor, currency },
      occurredOn: values.occurredOn,
      description,
      category: values.category as Checked['category'],
      paidBy: values.paidBy,
    }
  })
}

/** What is typed in the table, kept per method so switching back and forth loses nothing. */
type Typed = Record<Exclude<SplitMethod, 'EQUALLY'>, Record<string, string>>

function startingSplit(editing: GroupExpense | undefined, language: string) {
  const typed: Typed = { EXACT_AMOUNTS: {}, PERCENTAGES: {}, SHARES: {} }
  if (editing === undefined) {
    return { method: 'EQUALLY' as SplitMethod, typed, taking: undefined }
  }
  const { method, members } = editing.split
  for (const { userId, value } of members) {
    if (method === 'EXACT_AMOUNTS') {
      typed.EXACT_AMOUNTS[userId] = moneyInputText(
        { amountMinor: value, currency: editing.amount.currency },
        language,
      )
    } else if (method === 'PERCENTAGES') {
      typed.PERCENTAGES[userId] = percentInputText(BigInt(value), language)
    } else if (method === 'SHARES') {
      typed.SHARES[userId] = String(value)
    }
  }
  return {
    method,
    typed,
    taking: Object.fromEntries(members.map(({ userId }) => [userId, true])),
  }
}

function GroupExpenseForm({
  group,
  editing,
  onSaved,
}: {
  group: Group
  editing: GroupExpense | undefined
  onSaved: (saved: GroupExpense) => void
}) {
  const { t, i18n: translator } = useTranslation()
  const language = translator.resolvedLanguage ?? 'en'
  const queryClient = useQueryClient()
  const me = useCurrentUser().data?.id
  const currency = group.currency
  // One key for as long as the dialog is open: whatever is retried or changed after a failure,
  // the API records this expense at most once (useMoneyMutation, `idempotencyKey`).
  const [idempotencyKey] = useState(() => crypto.randomUUID())
  const create = useCreateGroupExpense(group.id, idempotencyKey)
  const reverse = useReverseGroupExpense(group.id, idempotencyKey)
  const ids = {
    amount: useId(),
    amountError: useId(),
    currency: useId(),
    description: useId(),
    descriptionError: useId(),
    date: useId(),
    dateError: useId(),
    payer: useId(),
    payerError: useId(),
    categoryError: useId(),
    status: useId(),
  }

  // The signed-in person first, as in the design canvas, then the others in the order they joined.
  const members = [
    ...group.members.filter((member) => member.userId === me),
    ...group.members.filter((member) => member.userId !== me),
  ]
  const memberIds = new Set(members.map((member) => member.userId))

  const [start] = useState(() => startingSplit(editing, language))
  const [method, setMethod] = useState<SplitMethod>(start.method)
  const [typed, setTyped] = useState<Typed>(start.typed)
  // Everyone takes part in a new expense until they are unticked.
  const [taking, setTaking] = useState<Record<string, boolean>>(
    () =>
      start.taking ??
      Object.fromEntries(members.map((member) => [member.userId, true])),
  )

  const defaultPayer =
    editing?.paidBy ?? (me !== undefined && memberIds.has(me) ? me : '')
  const form = useForm<FormValues, unknown, Checked>({
    resolver: zodResolver(checkedSchema(currency)),
    defaultValues: {
      amount:
        editing === undefined ? '' : moneyInputText(editing.amount, language),
      description: editing?.description ?? '',
      occurredOn: editing?.occurredOn ?? today(),
      paidBy: defaultPayer || (members[0]?.userId ?? ''),
      category: editing?.category ?? '',
    },
  })
  const amountText = useWatch({ control: form.control, name: 'amount' })

  const rows = members.map((member) => ({
    member: member.userId,
    taking: taking[member.userId] ?? false,
    typed: method === 'EQUALLY' ? '' : (typed[method][member.userId] ?? ''),
  }))
  const preview = splitPreview(amountText, currency, method, rows)

  const submit = form.handleSubmit(async (checked) => {
    if (!preview.ok) {
      return
    }
    const expense: NewGroupExpense = {
      ...checked,
      split: { method, members: preview.members },
    }
    try {
      if (editing === undefined) {
        onSaved(await create.mutateAsync(expense))
      } else if (sameGroupExpense(expense, asNewGroupExpense(editing))) {
        onSaved(editing)
      } else {
        const detail = await reverse.mutateAsync({
          expenseId: editing.id,
          replacement: expense,
        })
        onSaved(detail.expense)
      }
    } catch (error) {
      if (
        error instanceof ApiError &&
        (error.code === 'idempotency_key_reused' ||
          error.code === 'group_expense_already_reversed')
      ) {
        // Saved by an earlier attempt, or changed elsewhere: the group's list is behind.
        void queryClient.invalidateQueries({ queryKey: groupKey(group.id) })
      }
      form.setError('root.server', { message: apiErrorMessage(error, t) })
    }
  })

  const { symbol, symbolFirst, zero } = currencyLayout(currency, language)

  return (
    <form onSubmit={submit} noValidate className="flex min-h-0 flex-1 flex-col">
      <div className="flex min-h-0 flex-1 flex-col gap-4.5 overflow-y-auto px-5 pt-3 pb-5 sm:px-6">
        <Controller
          name="amount"
          control={form.control}
          render={({ field, fieldState }) => (
            <Field data-invalid={fieldState.invalid} className="gap-1.5">
              <FieldLabel htmlFor={ids.amount}>
                {t('transactions.form.amount')}
              </FieldLabel>
              <InputGroup className="h-14 rounded-xl">
                <InputGroupInput
                  {...field}
                  id={ids.amount}
                  type="text"
                  // The decimal keypad on phones, with the comma or the point of their region.
                  inputMode="decimal"
                  autoComplete="off"
                  spellCheck={false}
                  placeholder={zero}
                  aria-invalid={fieldState.invalid}
                  aria-describedby={
                    fieldState.invalid
                      ? `${ids.currency} ${ids.amountError}`
                      : ids.currency
                  }
                  className="h-full text-[28px] font-semibold tracking-tight tabular-nums md:text-[28px]"
                />
                {symbolFirst ? (
                  <InputGroupAddon
                    align="inline-start"
                    aria-hidden
                    className="pl-3.5 text-[28px] font-semibold tracking-tight text-inherit"
                  >
                    {symbol}
                  </InputGroupAddon>
                ) : (
                  <InputGroupAddon
                    align="inline-end"
                    aria-hidden
                    className="text-[28px] font-semibold text-inherit"
                  >
                    {symbol}
                  </InputGroupAddon>
                )}
                <InputGroupAddon align="inline-end" className="pr-3.5">
                  <span
                    id={ids.currency}
                    className="rounded-md bg-muted px-2 py-0.5 text-xs font-semibold text-foreground/70"
                  >
                    {currency}
                  </span>
                </InputGroupAddon>
              </InputGroup>
              <FieldError id={ids.amountError} errors={[fieldState.error]} />
            </Field>
          )}
        />

        <Controller
          name="description"
          control={form.control}
          render={({ field, fieldState }) => (
            <Field data-invalid={fieldState.invalid} className="gap-1.5">
              <FieldLabel htmlFor={ids.description}>
                {t('transactions.form.description')}
              </FieldLabel>
              <Input
                {...field}
                id={ids.description}
                autoComplete="off"
                placeholder={t('groupExpenses.form.descriptionPlaceholder')}
                aria-invalid={fieldState.invalid}
                aria-describedby={
                  fieldState.invalid ? ids.descriptionError : undefined
                }
                className="h-10"
              />
              <FieldError
                id={ids.descriptionError}
                errors={[fieldState.error]}
              />
            </Field>
          )}
        />

        <div className="grid grid-cols-1 gap-x-3 gap-y-4.5 sm:grid-cols-2">
          <Controller
            name="occurredOn"
            control={form.control}
            render={({ field, fieldState }) => (
              <Field data-invalid={fieldState.invalid} className="gap-1.5">
                <FieldLabel htmlFor={ids.date}>
                  {t('transactions.form.date')}
                </FieldLabel>
                <Input
                  {...field}
                  id={ids.date}
                  type="date"
                  aria-invalid={fieldState.invalid}
                  aria-describedby={
                    fieldState.invalid ? ids.dateError : undefined
                  }
                  className="h-10"
                />
                <FieldError id={ids.dateError} errors={[fieldState.error]} />
              </Field>
            )}
          />
          <Controller
            name="paidBy"
            control={form.control}
            render={({ field, fieldState }) => (
              <Field data-invalid={fieldState.invalid} className="gap-1.5">
                <FieldLabel htmlFor={ids.payer}>
                  {t('groupExpenses.form.paidBy')}
                </FieldLabel>
                <NativeSelect
                  {...field}
                  id={ids.payer}
                  className="h-10 w-full"
                  aria-invalid={fieldState.invalid}
                  aria-describedby={
                    fieldState.invalid ? ids.payerError : undefined
                  }
                >
                  {field.value === '' && (
                    <NativeSelectOption value="" disabled>
                      {t('groupExpenses.form.pickPayer')}
                    </NativeSelectOption>
                  )}
                  {members.map((member) => (
                    <NativeSelectOption
                      key={member.userId}
                      value={member.userId}
                    >
                      {member.userId === me
                        ? t('group.youWithName', { name: member.name })
                        : member.name}
                    </NativeSelectOption>
                  ))}
                </NativeSelect>
                <FieldError id={ids.payerError} errors={[fieldState.error]} />
              </Field>
            )}
          />
        </div>

        <FieldSet
          className="gap-0"
          data-invalid={form.formState.errors.category !== undefined}
          aria-describedby={
            form.formState.errors.category === undefined
              ? undefined
              : ids.categoryError
          }
        >
          <FieldLegend variant="label" className="mb-2">
            {t('transactions.form.category')}
          </FieldLegend>
          <div className="flex flex-wrap gap-2">
            {groupExpenseCategories.map((category) => {
              return (
                <label
                  key={category}
                  className="inline-flex h-9 cursor-pointer items-center gap-1.5 rounded-full border bg-background px-3 font-medium transition-colors hover:bg-muted/60 has-checked:border-primary has-checked:bg-primary has-checked:text-primary-foreground has-focus-visible:ring-3 has-focus-visible:ring-ring/50"
                >
                  <input
                    type="radio"
                    value={category}
                    className="sr-only"
                    aria-invalid={form.formState.errors.category !== undefined}
                    {...form.register('category')}
                  />
                  <GroupCategoryIcon
                    category={category}
                    aria-hidden
                    className="size-3.75"
                  />
                  {groupCategoryName(category, t)}
                </label>
              )
            })}
          </div>
          {form.formState.errors.category !== undefined && (
            <p
              id={ids.categoryError}
              role="alert"
              className="mt-2 text-sm text-destructive"
            >
              {form.formState.errors.category.message}
            </p>
          )}
        </FieldSet>

        <FieldSet className="gap-0">
          <FieldLegend variant="label" className="mb-2">
            {t('groupExpenses.form.split')}
          </FieldLegend>
          <div className="grid grid-cols-2 gap-0.5 rounded-[10px] bg-muted p-[3px] sm:grid-cols-4">
            {splitMethods.map((option) => (
              <label
                key={option}
                className="flex h-8.5 cursor-pointer items-center justify-center rounded-lg px-1.5 text-[13px] font-medium whitespace-nowrap text-muted-foreground transition-colors has-checked:bg-background has-checked:text-foreground has-checked:shadow-xs has-focus-visible:ring-3 has-focus-visible:ring-ring/50"
              >
                <input
                  type="radio"
                  name={`${ids.status}-method`}
                  value={option}
                  checked={method === option}
                  onChange={() => setMethod(option)}
                  className="sr-only"
                />
                {t(`groupExpenses.methods.${option}`)}
              </label>
            ))}
          </div>
        </FieldSet>

        <SplitTable
          members={members}
          me={me}
          method={method}
          currency={currency}
          language={language}
          taking={taking}
          typed={typed}
          preview={preview}
          onTake={(userId, take) =>
            setTaking((before) => ({ ...before, [userId]: take }))
          }
          onType={(userId, text) => {
            if (method !== 'EQUALLY') {
              setTyped((before) => ({
                ...before,
                [method]: { ...before[method], [userId]: text },
              }))
            }
          }}
        />

        <p
          id={ids.status}
          aria-live="polite"
          className={cn(
            '-mt-1 flex items-start gap-2 text-[13px] font-medium',
            preview.ok
              ? 'text-income-text'
              : 'text-amber-700 dark:text-amber-500',
          )}
        >
          {preview.ok ? (
            <CircleCheck aria-hidden className="mt-0.5 size-4 flex-none" />
          ) : (
            <CircleAlert aria-hidden className="mt-0.5 size-4 flex-none" />
          )}
          {statusText(preview, amountText, currency, language, t)}
        </p>

        {editing !== undefined && (
          <p className="flex items-start gap-2 rounded-[10px] bg-muted px-3 py-2.5 text-[13px] text-foreground/70">
            <Info aria-hidden className="mt-0.5 size-4 flex-none" />
            {t('groupExpenses.form.editNote')}
          </p>
        )}

        <FormError form={form} />
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3 border-t px-5 py-4 sm:px-6">
        <span className="inline-flex items-center gap-1.5 text-xs text-muted-foreground">
          <ShieldCheck aria-hidden className="size-3.5" />
          {t('transactions.form.savedOnce')}
        </span>
        <div className="ml-auto flex gap-2">
          <DialogClose asChild>
            <Button
              type="button"
              variant="outline"
              size="lg"
              className="h-10 px-4"
            >
              {t('common.cancel')}
            </Button>
          </DialogClose>
          <Button
            type="submit"
            size="lg"
            className="h-10 px-4"
            // Saves only once the split adds up; the line above says what is missing.
            disabled={!preview.ok || form.formState.isSubmitting}
            aria-describedby={ids.status}
          >
            {editing === undefined
              ? t('groupExpenses.form.submit')
              : t('transactions.form.saveChanges')}
          </Button>
        </div>
      </div>
    </form>
  )
}

/** "Who takes part": a row per member, ticked or not, with their input and their share. */
function SplitTable({
  members,
  me,
  method,
  currency,
  language,
  taking,
  typed,
  preview,
  onTake,
  onType,
}: {
  members: Group['members']
  me: string | undefined
  method: SplitMethod
  currency: string
  language: string
  taking: Record<string, boolean>
  typed: Typed
  preview: SplitPreview
  onTake: (userId: string, take: boolean) => void
  onType: (userId: string, text: string) => void
}) {
  const { t } = useTranslation()
  const idPrefix = useId()
  const { symbol, symbolFirst, zero } = currencyLayout(currency, language)
  const columns =
    'grid grid-cols-[1.25rem_minmax(0,1fr)_5.5rem_4.75rem] items-center gap-x-2 sm:grid-cols-[1.5rem_1.75rem_minmax(0,1fr)_7rem_5.75rem] sm:gap-x-2.5'

  return (
    <FieldSet className="gap-0">
      <FieldLegend variant="label" className="mb-1">
        {t('groupExpenses.form.takingPart')}
      </FieldLegend>
      <div
        aria-hidden
        className={cn(
          columns,
          'pt-1 pb-1.5 text-xs font-medium text-muted-foreground',
        )}
      >
        <span className="col-span-2 sm:col-span-3" />
        <span className="text-right">
          {method === 'EQUALLY'
            ? ''
            : t(`groupExpenses.form.inputHeader.${method}`)}
        </span>
        <span className="text-right">
          {t('groupExpenses.form.shareHeader')}
        </span>
      </div>
      <ul className="border-t">
        {members.map((member) => {
          const id = `${idPrefix}-${member.userId}`
          const on = taking[member.userId] ?? false
          const memberName = member.userId === me ? t('group.you') : member.name
          const share = preview.shares.get(member.userId)
          return (
            <li
              key={member.userId}
              className={cn(columns, 'min-h-12 border-b py-1.5')}
            >
              <input
                id={id}
                type="checkbox"
                checked={on}
                onChange={(event) =>
                  onTake(member.userId, event.target.checked)
                }
                className="size-4.5 cursor-pointer accent-primary"
              />
              {/* Wrapped: the avatar sets its own display, which would win over `hidden`. On a
                  phone the column goes, so the name and the amounts keep their room. */}
              <span className="hidden sm:block">
                <PersonAvatar
                  person={member}
                  className={cn('size-7 text-xs', !on && 'opacity-45')}
                />
              </span>
              <label
                htmlFor={id}
                className={cn(
                  'min-w-0 cursor-pointer truncate font-medium',
                  !on && 'text-muted-foreground',
                )}
              >
                {memberName}
              </label>
              <span className="flex justify-end">
                {on && method !== 'EQUALLY' && (
                  <InputGroup className="h-9 w-full rounded-lg">
                    <InputGroupInput
                      type="text"
                      inputMode={method === 'SHARES' ? 'numeric' : 'decimal'}
                      autoComplete="off"
                      spellCheck={false}
                      aria-label={t(`groupExpenses.form.inputLabel.${method}`, {
                        name:
                          member.userId === me
                            ? t('groupExpenses.youObject')
                            : member.name,
                      })}
                      value={typed[method][member.userId] ?? ''}
                      onChange={(event) =>
                        onType(member.userId, event.target.value)
                      }
                      placeholder={method === 'EXACT_AMOUNTS' ? zero : '0'}
                      className="h-full px-2 text-right tabular-nums"
                    />
                    {method === 'EXACT_AMOUNTS' && (
                      <InputGroupAddon
                        align={symbolFirst ? 'inline-start' : 'inline-end'}
                        aria-hidden
                        className={cn(
                          'text-[13px] text-muted-foreground',
                          symbolFirst ? 'pl-2' : 'pr-2',
                        )}
                      >
                        {symbol}
                      </InputGroupAddon>
                    )}
                    {method === 'PERCENTAGES' && (
                      <InputGroupAddon
                        align="inline-end"
                        aria-hidden
                        className="pr-2 text-[13px] text-muted-foreground"
                      >
                        %
                      </InputGroupAddon>
                    )}
                  </InputGroup>
                )}
              </span>
              <span
                className={cn(
                  'text-right tabular-nums',
                  on ? 'font-medium' : 'text-xs text-muted-foreground',
                )}
              >
                {!on
                  ? t('groupExpenses.form.notTakingPart')
                  : share !== undefined
                    ? formatMoney(
                        { amountMinor: Number(share), currency },
                        language,
                      )
                    : '—'}
              </span>
            </li>
          )
        })}
      </ul>
      <div className={cn(columns, 'min-h-11 tabular-nums')}>
        <span className="col-span-2 font-semibold sm:col-span-3">
          {t('groupExpenses.form.total')}
        </span>
        <span className="text-right text-foreground/70">
          {inputTotalText(preview.inputTotal, currency, language, t)}
        </span>
        <span className="text-right font-semibold">
          {preview.total === undefined
            ? '—'
            : formatMoney(
                { amountMinor: Number(preview.total), currency },
                language,
              )}
        </span>
      </div>
    </FieldSet>
  )
}

function inputTotalText(
  total: SplitInputTotal | undefined,
  currency: string,
  language: string,
  t: TFunction,
) {
  switch (total?.kind) {
    case undefined:
      return ''
    case 'people':
      return t('groupExpenses.form.people', { count: total.count })
    case 'shares':
      return t('groupExpenses.form.shares', { count: Number(total.sum) })
    case 'percent':
      return formatBasisPoints(total.basisPoints, language)
    case 'amount':
      return formatMoney(
        { amountMinor: Number(total.amountMinor), currency },
        language,
      )
  }
}

/** The line under the table: "Adds up to €240.00", or what is missing until it does. */
function statusText(
  preview: SplitPreview,
  amountText: string,
  currency: string,
  language: string,
  t: TFunction,
) {
  const money = (amountMinor: bigint) =>
    formatMoney({ amountMinor: Number(amountMinor), currency }, language)
  if (preview.ok) {
    return t('groupExpenses.status.addsUp', { amount: money(preview.total!) })
  }
  const { problem } = preview
  switch (problem.kind) {
    case 'amount': {
      const parsed = parseMoneyInput(amountText, currency)
      return !parsed.ok && parsed.error !== 'empty'
        ? moneyInputMessage(parsed.error, currency)
        : t('groupExpenses.status.amount')
    }
    case 'nobody':
      return t('groupExpenses.status.nobody')
    case 'shares':
      return t('groupExpenses.status.shares')
    case 'tooManyShares':
      return t('groupExpenses.status.tooManyShares')
    case 'percent':
      return t('groupExpenses.status.percent')
    case 'percentLeft':
      return t('groupExpenses.status.percentLeft', {
        percent: formatBasisPoints(problem.basisPoints, language),
      })
    case 'percentOver':
      return t('groupExpenses.status.percentOver', {
        percent: formatBasisPoints(problem.basisPoints, language),
      })
    case 'exactMissing':
      return t('groupExpenses.status.exactMissing')
    case 'amountLeft':
      return t('groupExpenses.status.amountLeft', {
        amount: money(problem.amountMinor),
      })
    case 'amountOver':
      return t('groupExpenses.status.amountOver', {
        amount: money(problem.amountMinor),
      })
  }
}
