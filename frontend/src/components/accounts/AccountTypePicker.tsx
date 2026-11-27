import type { UseFormRegisterReturn } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import type { AccountType } from '@/api/accounts'
import { FieldLegend, FieldSet } from '@/components/ui/field'
import { accountTypeIcons } from '@/lib/accounts'

/**
 * The account types as radio buttons with their icons, two to a row: in the add-account dialog
 * every type, in the edit dialog the ones an account can change to.
 */
export function AccountTypePicker({
  types,
  field,
}: {
  types: readonly AccountType[]
  /** The form's `register('type')`. */
  field: UseFormRegisterReturn
}) {
  const { t } = useTranslation()
  return (
    <FieldSet className="gap-0">
      <FieldLegend variant="label">{t('accounts.form.type')}</FieldLegend>
      <div className="grid grid-cols-2 gap-2">
        {types.map((type) => {
          const Icon = accountTypeIcons[type]
          return (
            <label
              key={type}
              // At least as tall as the design's 44px; taller when a long name wraps on a phone.
              className="flex min-h-11 cursor-pointer items-center gap-2.5 rounded-[10px] border bg-background px-3 py-2 leading-tight font-medium transition-colors hover:bg-muted/50 has-checked:border-primary has-checked:bg-muted/50 has-focus-visible:ring-3 has-focus-visible:ring-ring/50"
            >
              <input type="radio" value={type} className="sr-only" {...field} />
              <Icon aria-hidden className="size-4 flex-none" />
              <span>{t(`accounts.types.${type}`)}</span>
            </label>
          )
        })}
      </div>
    </FieldSet>
  )
}
