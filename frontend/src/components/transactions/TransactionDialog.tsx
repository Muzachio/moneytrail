import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { ArrowRight, Info, Landmark, Plus, ShieldCheck } from 'lucide-react'
import { useId, useState } from 'react'
import {
  Controller,
  useForm,
  type UseFormRegister,
  useWatch,
} from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { z } from 'zod'
import { accountsKey, type Account } from '@/api/accounts'
import {
  type Category,
  type CategoryKind,
  useCreateCategory,
} from '@/api/categories'
import { ApiError } from '@/api/problem'
import {
  type NewTransaction,
  type Transaction,
  type TransactionType,
  transactionsKey,
  useCreateTransaction,
  useReverseTransaction,
} from '@/api/transactions'
import { FormError } from '@/components/FormError'
import { NameDialog } from '@/components/NameDialog'
import { LoadFailed, Loading } from '@/components/QueryState'
import { useLedgerNames } from '@/components/transactions/useLedgerNames'
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
import { CURRENCY } from '@/lib/accounts'
import { categoryIcon, categoryName } from '@/lib/categories'
import { isDay, today } from '@/lib/dates'
import { returnFocus } from '@/lib/focus'
import { apiErrorMessage, moneyInputMessage } from '@/lib/forms'
import {
  currencyLayout,
  formatMoney,
  moneyInputText,
  parseMoneyInput,
} from '@/lib/money'
import {
  asNewTransaction,
  sameTransaction,
  transactionTypes,
} from '@/lib/transactions'
import { cn } from '@/lib/utils'
import { i18n } from '@/i18n/i18n'

/** `TransactionDescription` in the spec allows 1 to 200 characters. */
const DESCRIPTION_MAX_LENGTH = 200

type TransactionDialogProps = {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** The transaction to edit, which fills the form in; a new transaction when absent. */
  editing?: Transaction
  /** Gets what was saved: the new transaction, or the correction that replaced `editing`. */
  onSaved?: (saved: Transaction) => void
  /** The button that opened the dialog, which gets the focus back when it closes. */
  returnFocusTo?: HTMLElement | null
}

/**
 * The new-transaction dialog of the design canvas, for an expense, an income or a transfer. Opened
 * with a transaction to edit, it starts filled in and saves the edit as a reversal of the
 * transaction plus its correction, in one request.
 */
export function TransactionDialog({
  open,
  onOpenChange,
  editing,
  onSaved,
  returnFocusTo,
}: TransactionDialogProps) {
  const { t } = useTranslation()
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        aria-describedby={undefined}
        className="flex max-h-[calc(100svh-2rem)] flex-col gap-0 p-0 sm:max-w-130"
        onCloseAutoFocus={(event) => returnFocus(event, returnFocusTo)}
      >
        <DialogHeader className="px-6 pt-5 pb-1">
          <DialogTitle className="text-lg font-semibold">
            {editing === undefined
              ? t('transactions.form.newTitle')
              : t('transactions.form.editTitle')}
          </DialogTitle>
        </DialogHeader>
        {/* Inside the content, so each opening starts a new form and a new Idempotency-Key. */}
        <TransactionForm
          editing={editing}
          onSaved={(saved) => {
            onSaved?.(saved)
            onOpenChange(false)
          }}
          onClose={() => onOpenChange(false)}
        />
      </DialogContent>
    </Dialog>
  )
}

const formSchema = z.object({
  type: z.enum(transactionTypes),
  amount: z.string(),
  description: z.string(),
  // One per kind, so switching from expense to income and back keeps each choice.
  expenseCategoryId: z.string(),
  incomeCategoryId: z.string(),
  accountId: z.string(),
  fromAccountId: z.string(),
  toAccountId: z.string(),
  occurredOn: z.string(),
})

type FormValues = z.input<typeof formSchema>
type FormField = keyof FormValues

function categoryField(type: TransactionType) {
  return type === 'INCOME' ? 'incomeCategoryId' : 'expenseCategoryId'
}

function categoryKind(type: TransactionType): CategoryKind {
  return type === 'INCOME' ? 'INCOME' : 'EXPENSE'
}

/**
 * What the form holds, checked as a whole so that every problem shows at once, and turned into the
 * body of `POST /v1/transactions`. Messages are looked up as the form is checked, so they come out
 * in the language the person is using then.
 */
const transactionSchema = formSchema.transform(
  (values, context): NewTransaction => {
    let valid = true
    const problem = (path: FormField, message: string) => {
      valid = false
      context.addIssue({ code: 'custom', path: [path], message })
    }

    const amount = parseMoneyInput(values.amount, CURRENCY)
    if (!amount.ok) {
      problem(
        'amount',
        amount.error === 'empty'
          ? i18n.t('transactions.form.amountRequired')
          : moneyInputMessage(amount.error, CURRENCY),
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

    const pickAccount = i18n.t('transactions.form.pickAccount')
    if (values.type === 'TRANSFER') {
      if (values.fromAccountId === '') {
        problem('fromAccountId', pickAccount)
      }
      if (values.toAccountId === '') {
        problem('toAccountId', pickAccount)
      } else if (values.toAccountId === values.fromAccountId) {
        problem('toAccountId', i18n.t('transactions.form.sameAccount'))
      }
    } else {
      if (values[categoryField(values.type)] === '') {
        problem(
          categoryField(values.type),
          i18n.t('transactions.form.pickCategory'),
        )
      }
      if (values.accountId === '') {
        problem('accountId', pickAccount)
      }
    }

    if (!valid || !amount.ok) {
      return z.NEVER
    }
    const common = {
      type: values.type,
      amount: { amountMinor: amount.amountMinor, currency: CURRENCY },
      occurredOn: values.occurredOn,
      description,
    }
    return values.type === 'TRANSFER'
      ? {
          ...common,
          fromAccountId: values.fromAccountId,
          toAccountId: values.toAccountId,
        }
      : {
          ...common,
          categoryId: values[categoryField(values.type)],
          accountId: values.accountId,
        }
  },
)

type FormProps = {
  editing: Transaction | undefined
  onSaved: (saved: Transaction) => void
  onClose: () => void
}

function TransactionForm({ editing, onSaved, onClose }: FormProps) {
  const { t } = useTranslation()
  const names = useLedgerNames()
  const allAccounts = names.accounts.data
  const allCategories = names.categories.data

  if (allAccounts === undefined || allCategories === undefined) {
    return (
      <div className="px-6 pb-6">
        {names.isError ? (
          <LoadFailed onRetry={names.refetch}>
            {t('transactions.form.loadFailed')}
          </LoadFailed>
        ) : (
          <Loading>{t('transactions.form.loading')}</Loading>
        )}
      </div>
    )
  }

  // Archived accounts and categories are not offered, except those the transaction being edited
  // already uses: the API lets a correction keep them, but not pick another one.
  const kept = new Set(
    editing === undefined
      ? []
      : [
          editing.accountId,
          editing.fromAccountId,
          editing.toAccountId,
          editing.categoryId,
        ],
  )
  const accounts = allAccounts.filter(
    (account) => !account.archived || kept.has(account.id),
  )
  const categories = allCategories.filter(
    (category) => !category.archived || kept.has(category.id),
  )

  if (accounts.length === 0) {
    return <NoAccounts onClose={onClose} />
  }
  return (
    <TransactionFields
      editing={editing}
      accounts={accounts}
      categories={categories}
      onSaved={onSaved}
    />
  )
}

function defaultValues(
  editing: Transaction | undefined,
  accounts: Account[],
  language: string,
): FormValues {
  const active = accounts.filter((account) => !account.archived)
  const first = active[0]?.id ?? ''
  const blank: FormValues = {
    type: 'EXPENSE',
    amount: '',
    description: '',
    expenseCategoryId: '',
    incomeCategoryId: '',
    accountId: first,
    fromAccountId: first,
    toAccountId: active[1]?.id ?? '',
    occurredOn: today(),
  }
  if (editing === undefined) {
    return blank
  }
  const values: FormValues = {
    ...blank,
    type: editing.type,
    amount: moneyInputText(editing.amount, language),
    description: editing.description,
    occurredOn: editing.occurredOn,
  }
  if (editing.type === 'TRANSFER') {
    values.fromAccountId = editing.fromAccountId ?? ''
    values.toAccountId = editing.toAccountId ?? ''
    // Should the edit turn it into an expense, it was paid from where the money left.
    values.accountId = editing.fromAccountId ?? first
  } else {
    values[categoryField(editing.type)] = editing.categoryId ?? ''
    values.accountId = editing.accountId ?? ''
    values.fromAccountId = editing.accountId ?? first
  }
  return values
}

function TransactionFields({
  editing,
  accounts,
  categories,
  onSaved,
}: {
  editing: Transaction | undefined
  accounts: Account[]
  categories: Category[]
  onSaved: (saved: Transaction) => void
}) {
  const { t, i18n: translator } = useTranslation()
  const language = translator.resolvedLanguage ?? 'en'
  const queryClient = useQueryClient()
  // One key for as long as the dialog is open: whatever is retried or changed after a failure,
  // the API records this transaction at most once (useMoneyMutation, `idempotencyKey`).
  const [idempotencyKey] = useState(() => crypto.randomUUID())
  const create = useCreateTransaction(idempotencyKey)
  const reverse = useReverseTransaction(idempotencyKey)
  const ids = {
    amount: useId(),
    amountError: useId(),
    currency: useId(),
    description: useId(),
    descriptionError: useId(),
    categoryError: useId(),
    account: useId(),
    accountError: useId(),
    from: useId(),
    fromError: useId(),
    to: useId(),
    toError: useId(),
    date: useId(),
    dateError: useId(),
  }

  const form = useForm<FormValues, unknown, NewTransaction>({
    resolver: zodResolver(transactionSchema),
    defaultValues: defaultValues(editing, accounts, language),
  })
  const type = useWatch({ control: form.control, name: 'type' })
  const [newCategoryButton, setNewCategoryButton] =
    useState<HTMLElement | null>(null)
  const [addingCategory, setAddingCategory] = useState(false)
  const createCategory = useCreateCategory()

  const submit = form.handleSubmit(async (transaction) => {
    try {
      if (editing === undefined) {
        onSaved(await create.mutateAsync(transaction))
      } else if (sameTransaction(transaction, asNewTransaction(editing))) {
        onSaved(editing)
      } else {
        const detail = await reverse.mutateAsync({
          id: editing.id,
          replacement: transaction,
        })
        onSaved(detail.transaction)
      }
    } catch (error) {
      if (error instanceof ApiError) {
        if (error.code === 'category_archived') {
          form.setError(
            categoryField(transaction.type),
            { message: t('errors.categoryArchived') },
            { shouldFocus: true },
          )
          return
        }
        if (
          error.code === 'idempotency_key_reused' ||
          error.code === 'transaction_already_reversed'
        ) {
          // Saved by an earlier attempt, or changed elsewhere: the lists are behind.
          void queryClient.invalidateQueries({ queryKey: transactionsKey })
          void queryClient.invalidateQueries({ queryKey: accountsKey })
        }
      }
      form.setError('root.server', { message: apiErrorMessage(error, t) })
    }
  })

  const kind = categoryKind(type)
  const kindCategories = categories.filter((category) => category.kind === kind)
  const { symbol, symbolFirst, zero } = currencyLayout(CURRENCY, language)
  const sign = type === 'EXPENSE' ? '−' : type === 'INCOME' ? '+' : ''
  const accountOption = (account: Account) => {
    const name = account.archived
      ? t('transactions.form.archivedOption', { name: account.name })
      : account.name
    return t('transactions.form.accountOption', {
      name,
      balance: formatMoney(account.balance, language),
    })
  }
  const accountOptions = (value: string) => (
    <>
      {value === '' && (
        <NativeSelectOption value="" disabled>
          {t('transactions.form.pickAccount')}
        </NativeSelectOption>
      )}
      {accounts.map((account) => (
        <NativeSelectOption key={account.id} value={account.id}>
          {accountOption(account)}
        </NativeSelectOption>
      ))}
    </>
  )

  return (
    <>
      <form
        onSubmit={submit}
        noValidate
        className="flex min-h-0 flex-1 flex-col"
      >
        <div className="flex min-h-0 flex-1 flex-col gap-4.5 overflow-y-auto px-6 pt-3 pb-5">
          <FieldSet className="gap-0">
            <FieldLegend className="sr-only">
              {t('transactions.form.type')}
            </FieldLegend>
            <div className="grid grid-cols-3 gap-0.5 rounded-[10px] bg-muted p-[3px]">
              {transactionTypes.map((option) => (
                <label
                  key={option}
                  className="flex h-8.5 cursor-pointer items-center justify-center rounded-lg font-medium text-muted-foreground transition-colors has-checked:bg-background has-checked:text-foreground has-checked:shadow-xs has-focus-visible:ring-3 has-focus-visible:ring-ring/50"
                >
                  <input
                    type="radio"
                    value={option}
                    className="sr-only"
                    {...form.register('type')}
                  />
                  {t(`transactions.types.${option}`)}
                </label>
              ))}
            </div>
          </FieldSet>

          <Controller
            name="amount"
            control={form.control}
            render={({ field, fieldState }) => (
              <Field data-invalid={fieldState.invalid} className="gap-1.5">
                <FieldLabel htmlFor={ids.amount}>
                  {t('transactions.form.amount')}
                </FieldLabel>
                <InputGroup
                  className={cn(
                    'h-14 rounded-xl',
                    type === 'INCOME' && 'text-income-text',
                  )}
                >
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
                    className="h-full text-[28px] font-semibold tracking-tight text-inherit tabular-nums md:text-[28px]"
                  />
                  {(sign !== '' || symbolFirst) && (
                    // The sign repeats the type chosen above; screen readers have that already.
                    <InputGroupAddon
                      align="inline-start"
                      aria-hidden
                      className="pl-3.5 text-[28px] font-semibold tracking-tight text-inherit"
                    >
                      {sign}
                      {symbolFirst && symbol}
                    </InputGroupAddon>
                  )}
                  {!symbolFirst && (
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
                      {CURRENCY}
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
                  placeholder={t(
                    `transactions.form.descriptionPlaceholder.${type}`,
                  )}
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

          {type !== 'TRANSFER' && (
            <CategoryChips
              key={kind}
              kind={kind}
              categories={kindCategories}
              name={categoryField(type)}
              register={form.register}
              error={form.formState.errors[categoryField(type)]?.message}
              errorId={ids.categoryError}
              onNewCategory={(button) => {
                setNewCategoryButton(button)
                setAddingCategory(true)
              }}
            />
          )}

          {type === 'TRANSFER' ? (
            <div className="grid grid-cols-[minmax(0,1fr)_1.5rem_minmax(0,1fr)] items-start gap-2">
              <Controller
                name="fromAccountId"
                control={form.control}
                render={({ field, fieldState }) => (
                  <Field data-invalid={fieldState.invalid} className="gap-1.5">
                    <FieldLabel htmlFor={ids.from}>
                      {t('transactions.form.from')}
                    </FieldLabel>
                    <NativeSelect
                      {...field}
                      id={ids.from}
                      className="h-10 w-full"
                      aria-invalid={fieldState.invalid}
                      aria-describedby={
                        fieldState.invalid ? ids.fromError : undefined
                      }
                    >
                      {accountOptions(field.value)}
                    </NativeSelect>
                    <FieldError
                      id={ids.fromError}
                      errors={[fieldState.error]}
                    />
                  </Field>
                )}
              />
              <ArrowRight
                aria-hidden
                className="mt-[2.15rem] size-4 justify-self-center text-muted-foreground"
              />
              <Controller
                name="toAccountId"
                control={form.control}
                render={({ field, fieldState }) => (
                  <Field data-invalid={fieldState.invalid} className="gap-1.5">
                    <FieldLabel htmlFor={ids.to}>
                      {t('transactions.form.to')}
                    </FieldLabel>
                    <NativeSelect
                      {...field}
                      id={ids.to}
                      className="h-10 w-full"
                      aria-invalid={fieldState.invalid}
                      aria-describedby={
                        fieldState.invalid ? ids.toError : undefined
                      }
                    >
                      {accountOptions(field.value)}
                    </NativeSelect>
                    <FieldError id={ids.toError} errors={[fieldState.error]} />
                  </Field>
                )}
              />
            </div>
          ) : (
            <Controller
              name="accountId"
              control={form.control}
              render={({ field, fieldState }) => (
                <Field data-invalid={fieldState.invalid} className="gap-1.5">
                  <FieldLabel htmlFor={ids.account}>
                    {type === 'INCOME'
                      ? t('transactions.form.receivedIn')
                      : t('transactions.form.paidFrom')}
                  </FieldLabel>
                  <NativeSelect
                    {...field}
                    id={ids.account}
                    className="h-10 w-full"
                    aria-invalid={fieldState.invalid}
                    aria-describedby={
                      fieldState.invalid ? ids.accountError : undefined
                    }
                  >
                    {accountOptions(field.value)}
                  </NativeSelect>
                  <FieldError
                    id={ids.accountError}
                    errors={[fieldState.error]}
                  />
                </Field>
              )}
            />
          )}

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

          {type === 'TRANSFER' && (
            <Note>{t('transactions.form.transferNote')}</Note>
          )}
          {editing !== undefined && (
            <Note>{t('transactions.form.editNote')}</Note>
          )}

          <FormError form={form} />
        </div>

        <div className="flex flex-wrap items-center justify-between gap-3 border-t px-6 py-4">
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
              disabled={form.formState.isSubmitting}
            >
              {editing === undefined
                ? t(`transactions.form.submit.${type}`)
                : t('transactions.form.saveChanges')}
            </Button>
          </div>
        </div>
      </form>

      {/* Outside the form above: React sends a portal's events up its component tree, so a
          submit inside this dialog would also submit the transaction. */}
      <NameDialog
        open={addingCategory}
        onOpenChange={setAddingCategory}
        title={t(`categories.new.${kind}`)}
        submitLabel={t('categories.add')}
        onSave={async (name) => {
          const category = await createCategory.mutateAsync({ kind, name })
          form.setValue(categoryField(type), category.id, {
            shouldValidate: form.formState.isSubmitted,
          })
        }}
        nameError={(error) =>
          error instanceof ApiError && error.code === 'category_name_taken'
            ? t(`categories.nameTaken.${kind}`)
            : undefined
        }
        returnFocusTo={newCategoryButton}
      />
    </>
  )
}

/** The categories of one kind as chips, as in the design canvas, and "New category" after them. */
function CategoryChips({
  kind,
  categories,
  name,
  register,
  error,
  errorId,
  onNewCategory,
}: {
  kind: CategoryKind
  categories: Category[]
  name: 'expenseCategoryId' | 'incomeCategoryId'
  /** Called here, after the amount and the description: a failed check focuses the first field. */
  register: UseFormRegister<FormValues>
  error: string | undefined
  errorId: string
  /** Gets the chip, for the dialog to give the focus back to. */
  onNewCategory: (button: HTMLElement) => void
}) {
  const { t } = useTranslation()
  return (
    <FieldSet
      className="gap-0"
      data-invalid={error !== undefined}
      aria-describedby={error === undefined ? undefined : errorId}
    >
      <FieldLegend variant="label" className="mb-2">
        {t('transactions.form.category')}
      </FieldLegend>
      <div className="flex flex-wrap gap-2">
        {categories
          .filter((category) => category.kind === kind)
          .map((category) => {
            const Icon = categoryIcon(category)
            return (
              <label
                key={category.id}
                className="inline-flex h-9 cursor-pointer items-center gap-1.5 rounded-full border bg-background px-3 font-medium transition-colors hover:bg-muted/60 has-checked:border-primary has-checked:bg-primary has-checked:text-primary-foreground has-focus-visible:ring-3 has-focus-visible:ring-ring/50"
              >
                <input
                  type="radio"
                  value={category.id}
                  className="sr-only"
                  aria-invalid={error !== undefined}
                  {...register(name)}
                />
                <Icon aria-hidden className="size-3.75" />
                {category.archived
                  ? t('transactions.form.archivedOption', {
                      name: categoryName(category, t),
                    })
                  : categoryName(category, t)}
              </label>
            )
          })}
        <button
          type="button"
          onClick={(event) => onNewCategory(event.currentTarget)}
          className="inline-flex h-9 items-center gap-1.5 rounded-full border border-dashed border-foreground/20 bg-background px-3 font-medium text-foreground/70 transition-colors outline-none hover:bg-muted/60 focus-visible:ring-3 focus-visible:ring-ring/50"
        >
          <Plus aria-hidden className="size-3.75" />
          {t('transactions.form.newCategory')}
        </button>
      </div>
      {error !== undefined && (
        <p id={errorId} role="alert" className="mt-2 text-sm text-destructive">
          {error}
        </p>
      )}
    </FieldSet>
  )
}

function Note({ children }: { children: string }) {
  return (
    <p className="flex items-start gap-2 rounded-[10px] bg-muted px-3 py-2.5 text-[13px] text-foreground/70">
      <Info aria-hidden className="mt-0.5 size-4 flex-none" />
      {children}
    </p>
  )
}

/** Money moves from or to an account, so there must be one before a transaction. */
function NoAccounts({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation()
  return (
    <div className="flex flex-col items-center gap-3 px-6 pt-4 pb-8 text-center">
      <span className="grid size-10 place-items-center rounded-[10px] bg-muted text-foreground/80">
        <Landmark aria-hidden className="size-5" />
      </span>
      <p className="font-semibold">{t('transactions.form.noAccounts.title')}</p>
      <p className="max-w-sm text-muted-foreground">
        {t('transactions.form.noAccounts.description')}
      </p>
      <Button asChild size="lg">
        <Link to="/accounts" onClick={onClose}>
          {t('transactions.form.noAccounts.action')}
        </Link>
      </Button>
    </div>
  )
}
