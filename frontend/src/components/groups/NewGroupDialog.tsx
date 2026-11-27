import { zodResolver } from '@hookform/resolvers/zod'
import { useId } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { z } from 'zod'
import { type Group, useCreateGroup } from '@/api/groups'
import { FormError } from '@/components/FormError'
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
} from '@/components/ui/field'
import { Input } from '@/components/ui/input'
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select'
import { returnFocus } from '@/lib/focus'
import { apiErrorMessage, nameSchema } from '@/lib/forms'

/** The currencies a group can have: `CurrencyCode` in the spec, only EUR for now. */
const currencies = ['EUR'] as const

const schema = z.object({
  name: nameSchema,
  currency: z.enum(currencies),
})

/** The new-group dialog of the design canvas: a name and the group's one currency. */
export function NewGroupDialog({
  open,
  onOpenChange,
  onCreated,
  returnFocusTo,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Gets the new group, to open its page, where the people come in through invite links. */
  onCreated: (group: Group) => void
  /** The button that opened the dialog, which gets the focus back when it closes. */
  returnFocusTo?: HTMLElement | null
}) {
  const { t } = useTranslation()
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        aria-describedby={undefined}
        className="max-h-[calc(100svh-2rem)] gap-5 overflow-y-auto p-6 sm:max-w-105"
        onCloseAutoFocus={(event) => returnFocus(event, returnFocusTo)}
      >
        <DialogHeader>
          <DialogTitle className="text-lg font-semibold">
            {t('groups.new.title')}
          </DialogTitle>
        </DialogHeader>
        {/* Inside the content, so each opening starts a new form. */}
        <NewGroupForm
          onCreated={(group) => {
            onOpenChange(false)
            onCreated(group)
          }}
        />
      </DialogContent>
    </Dialog>
  )
}

function NewGroupForm({ onCreated }: { onCreated: (group: Group) => void }) {
  const { t, i18n } = useTranslation()
  const nameId = useId()
  const nameErrorId = useId()
  const currencyId = useId()
  const currencyHintId = useId()
  const create = useCreateGroup()
  const form = useForm({
    resolver: zodResolver(schema),
    defaultValues: { name: '', currency: 'EUR' as const },
  })
  const currencyNames = new Intl.DisplayNames(i18n.language, {
    type: 'currency',
  })

  const submit = form.handleSubmit(async (group) => {
    try {
      onCreated(await create.mutateAsync(group))
    } catch (error) {
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
            <FieldLabel htmlFor={nameId}>{t('forms.name')}</FieldLabel>
            <Input
              {...field}
              id={nameId}
              autoComplete="off"
              placeholder={t('groups.new.namePlaceholder')}
              aria-invalid={fieldState.invalid}
              aria-describedby={fieldState.invalid ? nameErrorId : undefined}
              className="h-10"
            />
            <FieldError id={nameErrorId} errors={[fieldState.error]} />
          </Field>
        )}
      />
      <Field>
        <FieldLabel htmlFor={currencyId}>{t('groups.new.currency')}</FieldLabel>
        <NativeSelect
          id={currencyId}
          aria-describedby={currencyHintId}
          className="h-10 w-full"
          {...form.register('currency')}
        >
          {currencies.map((currency) => (
            <NativeSelectOption key={currency} value={currency}>
              {`${currency} · ${currencyNames.of(currency) ?? currency}`}
            </NativeSelectOption>
          ))}
        </NativeSelect>
        <FieldDescription id={currencyHintId}>
          {t('groups.new.currencyHint')}
        </FieldDescription>
      </Field>
      <p className="rounded-lg bg-muted px-3 py-2.5 text-sm text-foreground/75">
        {t('groups.new.next')}
      </p>
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
          {t('groups.new.create')}
        </Button>
      </DialogFooter>
    </form>
  )
}
