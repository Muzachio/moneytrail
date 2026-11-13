import { zodResolver } from '@hookform/resolvers/zod'
import { Lock } from 'lucide-react'
import { useId } from 'react'
import { Controller, useForm, useWatch } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { z } from 'zod'
import { type NewAccount, useCreateAccount } from '@/api/accounts'
import { FormError } from '@/components/FormError'
import { MoneyInput } from '@/components/MoneyInput'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  Field,
  FieldDescription,
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
import {
  accountTypeIcons,
  accountTypes,
  CURRENCY,
  isLiability,
} from '@/lib/accounts'
import { returnFocus } from '@/lib/focus'
import { apiErrorMessage, moneyInputMessage, nameSchema } from '@/lib/forms'
import { parseMoneyInput } from '@/lib/money'

/** The add-account dialog of the design canvas. */
export function AddAccountDialog({
  open,
  onOpenChange,
  returnFocusTo,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** The button that opened the dialog, which gets the focus back when it closes. */
  returnFocusTo?: HTMLElement | null
}) {
  const { t } = useTranslation()
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        aria-describedby={undefined}
        className="max-h-[calc(100svh-2rem)] gap-5 overflow-y-auto p-6 sm:max-w-110"
        onCloseAutoFocus={(event) => returnFocus(event, returnFocusTo)}
      >
        <DialogHeader>
          <DialogTitle className="text-lg font-semibold">
            {t('accounts.form.title')}
          </DialogTitle>
        </DialogHeader>
        {/* Inside the content, so each opening starts a new form and a new Idempotency-Key. */}
        <AddAccountForm onAdded={() => onOpenChange(false)} />
      </DialogContent>
    </Dialog>
  )
}

const addAccountSchema = z.object({
  name: nameSchema,
  type: z.enum(accountTypes),
  // What was typed, as a positive number of minor units; an empty field is zero.
  balance: z.string().transform((text, context) => {
    if (text.trim() === '') {
      return 0
    }
    const parsed = parseMoneyInput(text, CURRENCY)
    if (parsed.ok) {
      return parsed.amountMinor
    }
    // `empty` is ruled out above.
    const error = parsed.error === 'empty' ? 'invalid' : parsed.error
    context.addIssue({
      code: 'custom',
      message: moneyInputMessage(error, CURRENCY),
    })
    return z.NEVER
  }),
})

type AddAccountValues = z.output<typeof addAccountSchema>

/**
 * The body of `POST /v1/accounts`. A credit card's balance is what the person owes, typed as a
 * positive amount; the ledger keeps it negative. A zero balance records no opening entry.
 */
function newAccount({ name, type, balance }: AddAccountValues): NewAccount {
  const account: NewAccount = { name, type, currency: CURRENCY }
  if (balance !== 0) {
    account.openingBalance = {
      amountMinor: isLiability(type) ? -balance : balance,
      currency: CURRENCY,
    }
  }
  return account
}

function AddAccountForm({ onAdded }: { onAdded: () => void }) {
  const { t } = useTranslation()
  const createAccount = useCreateAccount()
  const ids = {
    name: useId(),
    nameError: useId(),
    currency: useId(),
    balance: useId(),
    balanceHelp: useId(),
    balanceError: useId(),
  }
  const form = useForm<
    z.input<typeof addAccountSchema>,
    unknown,
    AddAccountValues
  >({
    resolver: zodResolver(addAccountSchema),
    defaultValues: { name: '', type: 'CHECKING', balance: '' },
  })
  const owes = isLiability(useWatch({ control: form.control, name: 'type' }))

  const submit = form.handleSubmit(async (values) => {
    try {
      await createAccount.mutateAsync(newAccount(values))
      onAdded()
    } catch (error) {
      // The API accepts any name and any amount the form lets through, so what fails here is
      // about the request as a whole: the network, the session, or the API itself.
      form.setError('root.server', { message: apiErrorMessage(error, t) })
    }
  })

  return (
    <form onSubmit={submit} noValidate className="flex flex-col gap-5">
      <Controller
        name="name"
        control={form.control}
        render={({ field, fieldState }) => (
          <Field data-invalid={fieldState.invalid}>
            <FieldLabel htmlFor={ids.name}>{t('forms.name')}</FieldLabel>
            <Input
              {...field}
              id={ids.name}
              autoComplete="off"
              placeholder={t('accounts.form.namePlaceholder')}
              aria-invalid={fieldState.invalid}
              aria-describedby={fieldState.invalid ? ids.nameError : undefined}
              className="h-10"
            />
            <FieldError id={ids.nameError} errors={[fieldState.error]} />
          </Field>
        )}
      />

      <FieldSet className="gap-0">
        <FieldLegend variant="label">{t('accounts.form.type')}</FieldLegend>
        <div className="grid grid-cols-2 gap-2">
          {accountTypes.map((type) => {
            const Icon = accountTypeIcons[type]
            return (
              <label
                key={type}
                // At least as tall as the design's 44px; taller when a long name wraps on a phone.
                className="flex min-h-11 cursor-pointer items-center gap-2.5 rounded-[10px] border bg-background px-3 py-2 leading-tight font-medium transition-colors hover:bg-muted/50 has-checked:border-primary has-checked:bg-muted/50 has-focus-visible:ring-3 has-focus-visible:ring-ring/50"
              >
                <input
                  type="radio"
                  value={type}
                  className="sr-only"
                  {...form.register('type')}
                />
                <Icon aria-hidden className="size-4 flex-none" />
                <span>{t(`accounts.types.${type}`)}</span>
              </label>
            )
          })}
        </div>
      </FieldSet>

      <div className="flex flex-col gap-2">
        <div className="grid grid-cols-1 gap-3 min-[400px]:grid-cols-2">
          <Field>
            <FieldLabel htmlFor={ids.currency}>
              {t('accounts.form.currency')}
            </FieldLabel>
            {/* Only euros in M1; the field shows that a choice will come. */}
            <InputGroup className="h-10">
              <InputGroupInput
                id={ids.currency}
                value={t('accounts.form.euro')}
                disabled
                readOnly
              />
              <InputGroupAddon align="inline-end">
                <Lock aria-hidden />
              </InputGroupAddon>
            </InputGroup>
          </Field>
          <Controller
            name="balance"
            control={form.control}
            render={({ field, fieldState }) => (
              <Field data-invalid={fieldState.invalid}>
                <FieldLabel htmlFor={ids.balance}>
                  {owes
                    ? t('accounts.form.owedToday')
                    : t('accounts.form.balanceToday')}
                </FieldLabel>
                <MoneyInput
                  id={ids.balance}
                  currency={CURRENCY}
                  value={field.value}
                  onChange={field.onChange}
                  onBlur={field.onBlur}
                  name={field.name}
                  ref={field.ref}
                  className="h-10"
                  aria-invalid={fieldState.invalid}
                  aria-describedby={
                    fieldState.invalid
                      ? `${ids.balanceError} ${ids.balanceHelp}`
                      : ids.balanceHelp
                  }
                />
                <FieldError id={ids.balanceError} errors={[fieldState.error]} />
              </Field>
            )}
          />
        </div>
        <FieldDescription id={ids.balanceHelp} className="text-[13px]">
          {owes ? t('accounts.form.owedHelp') : t('accounts.form.balanceHelp')}{' '}
          {t('accounts.form.moreCurrencies')}
        </FieldDescription>
      </div>

      <FormError form={form} />

      <DialogFooter className="mx-0 mb-0 border-0 bg-transparent p-0">
        <DialogClose asChild>
          <Button type="button" variant="outline" size="lg" className="h-10">
            {t('common.cancel')}
          </Button>
        </DialogClose>
        <Button
          type="submit"
          size="lg"
          className="h-10"
          disabled={form.formState.isSubmitting}
        >
          {t('accounts.form.submit')}
        </Button>
      </DialogFooter>
    </form>
  )
}
