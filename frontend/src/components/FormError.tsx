import { CircleAlert } from 'lucide-react'
import type { FieldValues, UseFormReturn } from 'react-hook-form'

/**
 * The error of a whole form, such as an answer from the API that is not about one field. React
 * Hook Form clears it on the next submit.
 */
export function FormError<T extends FieldValues, C, R extends FieldValues>({
  form,
}: {
  form: UseFormReturn<T, C, R>
}) {
  const message = form.formState.errors.root?.server?.message
  if (message === undefined) {
    return null
  }
  return (
    <p
      role="alert"
      className="flex items-start gap-2 rounded-lg bg-destructive/10 px-3 py-2.5 text-sm text-destructive"
    >
      <CircleAlert aria-hidden className="mt-0.5 size-4 flex-none" />
      {message}
    </p>
  )
}
