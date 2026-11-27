import { zodResolver } from '@hookform/resolvers/zod'
import { useId } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { z } from 'zod'
import type { AccountType } from '@/api/accounts'
import type { CategoryIcon } from '@/api/categories'
import { AccountTypePicker } from '@/components/accounts/AccountTypePicker'
import { IconPicker } from '@/components/categories/IconPicker'
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
import { Field, FieldError, FieldLabel } from '@/components/ui/field'
import { Input } from '@/components/ui/input'
import { accountTypes } from '@/lib/accounts'
import { categoryIconNames } from '@/lib/categories'
import { returnFocus } from '@/lib/focus'
import { apiErrorMessage, nameSchema } from '@/lib/forms'

/** What the dialog saves: the name, and the icon or the account type when it offers them. */
export type NameDialogValues = {
  name: string
  icon?: CategoryIcon
  type?: AccountType
}

type NameDialogProps = {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  submitLabel: string
  /** The name the field starts with: the current one when renaming, empty when adding. */
  initialName?: string
  /**
   * Offers the category icons to pick from, with this one picked at first. Left out for an
   * account, and for a default category, which keeps the icon of its key.
   */
  initialIcon?: CategoryIcon
  /**
   * Offers these account types to pick from, with `initialType` picked at first: an asset account
   * can become another asset (#121). Left out for a credit card and for categories.
   */
  typeOptions?: readonly AccountType[]
  initialType?: AccountType
  placeholder?: string
  /** Sends the values; what it throws is shown in the form, and the dialog stays open. */
  onSave: (values: NameDialogValues) => Promise<unknown>
  /**
   * The message for an error that is about the name itself, such as a name already taken, shown
   * at the field. Other errors are shown for the whole form.
   */
  nameError?: (error: unknown) => string | undefined
  /** The button that opened the dialog, which gets the focus back when it closes. */
  returnFocusTo?: HTMLElement | null
}

/**
 * A dialog with a name field, and the category icons when it is for a category of the person's
 * own, or the account types when it edits an asset account: adding a category, or editing an
 * account or a category.
 */
export function NameDialog({
  open,
  onOpenChange,
  title,
  returnFocusTo,
  ...form
}: NameDialogProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      {/* Nothing to describe beyond the title and the field's label. */}
      <DialogContent
        aria-describedby={undefined}
        className="gap-5 p-6"
        onCloseAutoFocus={(event) => returnFocus(event, returnFocusTo)}
      >
        <DialogHeader>
          <DialogTitle className="text-lg font-semibold">{title}</DialogTitle>
        </DialogHeader>
        {/* Inside the content, so each opening starts a new form. */}
        <NameForm {...form} onSaved={() => onOpenChange(false)} />
      </DialogContent>
    </Dialog>
  )
}

const nameFormSchema = z.object({
  name: nameSchema,
  icon: z.enum(categoryIconNames).optional(),
  type: z.enum(accountTypes).optional(),
})

function NameForm({
  submitLabel,
  initialName = '',
  initialIcon,
  typeOptions,
  initialType,
  placeholder,
  onSave,
  nameError,
  onSaved,
}: Omit<NameDialogProps, 'open' | 'onOpenChange' | 'title'> & {
  onSaved: () => void
}) {
  const { t } = useTranslation()
  const nameId = useId()
  const errorId = useId()
  const form = useForm({
    resolver: zodResolver(nameFormSchema),
    defaultValues: { name: initialName, icon: initialIcon, type: initialType },
  })

  const submit = form.handleSubmit(async ({ name, icon, type }) => {
    // Saving a name that did not change would still turn a default category's translated name
    // into a fixed one, in one language.
    if (
      name === initialName.trim() &&
      icon === initialIcon &&
      type === initialType
    ) {
      onSaved()
      return
    }
    try {
      await onSave({ name, icon, type })
      onSaved()
    } catch (error) {
      const message = nameError?.(error)
      if (message !== undefined) {
        form.setError('name', { message }, { shouldFocus: true })
      } else {
        form.setError('root.server', { message: apiErrorMessage(error, t) })
      }
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
              placeholder={placeholder}
              aria-invalid={fieldState.invalid}
              aria-describedby={fieldState.invalid ? errorId : undefined}
              className="h-10"
            />
            <FieldError id={errorId} errors={[fieldState.error]} />
          </Field>
        )}
      />
      {initialIcon !== undefined && (
        <IconPicker field={form.register('icon')} />
      )}
      {typeOptions !== undefined && (
        <AccountTypePicker types={typeOptions} field={form.register('type')} />
      )}
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
          {submitLabel}
        </Button>
      </DialogFooter>
    </form>
  )
}
