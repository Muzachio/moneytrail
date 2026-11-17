import type { UseFormRegisterReturn } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { FieldLegend, FieldSet } from '@/components/ui/field'
import { categoryIconNames, categoryIcons } from '@/lib/categories'

/**
 * The icons a category of the person's own can have, as radio buttons: one is picked at a time,
 * and the arrow keys move between them. Each is named for screen readers, and on hover, by what it
 * shows, since the icon alone has no text.
 */
export function IconPicker({ field }: { field: UseFormRegisterReturn }) {
  const { t } = useTranslation()
  return (
    <FieldSet className="gap-0">
      <FieldLegend variant="label" className="mb-2">
        {t('categories.icon')}
      </FieldLegend>
      {/* Six to a row: four full rows of 24, narrow enough for the dialog on a 375 px phone. */}
      <div className="grid grid-cols-[repeat(6,2.5rem)] gap-2">
        {categoryIconNames.map((name) => {
          const Icon = categoryIcons[name]
          const label = t(`categories.icons.${name}`)
          return (
            <label
              key={name}
              title={label}
              className="grid size-10 cursor-pointer place-items-center rounded-lg border bg-background text-foreground/80 transition-colors hover:bg-muted/60 has-checked:border-primary has-checked:bg-primary has-checked:text-primary-foreground has-focus-visible:ring-3 has-focus-visible:ring-ring/50"
            >
              <input type="radio" value={name} className="sr-only" {...field} />
              <Icon aria-hidden className="size-4.5" />
              <span className="sr-only">{label}</span>
            </label>
          )
        })}
      </div>
    </FieldSet>
  )
}
