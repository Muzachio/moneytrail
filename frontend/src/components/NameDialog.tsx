import { zodResolver } from '@hookform/resolvers/zod'
import { useId } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { z } from 'zod'
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
import { returnFocus } from '@/lib/focus'
import { apiErrorMessage, nameSchema } from '@/lib/forms'

type NameDialogProps = {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  submitLabel: string
  /** The name the field starts with: the current one when renaming, empty when adding. */
  initialName?: string
  placeholder?: string
  /** Sends the name; what it throws is shown in the form, and the dialog stays open. */
  onSave: (name: string) => Promise<unknown>
  /**
   * The message for an error that is about the name itself, such as a name already taken, shown
   * at the field. Other errors are shown for the whole form.
   */
  nameError?: (error: unknown) => string | undefined
  /** The button that opened the dialog, which gets the focus back when it closes. */
  returnFocusTo?: HTMLElement | null
}

/** A dialog with a single name field: adding a category, or renaming an account or a category. */
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

const nameFormSchema = z.object({ name: nameSchema })

function NameForm({
  submitLabel,
  initialName = '',
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
    defaultValues: { name: initialName },
  })

  const submit = form.handleSubmit(async ({ name }) => {
    // Saving a name that did not change would still turn a default category's translated name
    // into a fixed one, in one language.
    if (name === initialName.trim()) {
      onSaved()
      return
    }
    try {
      await onSave(name)
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
