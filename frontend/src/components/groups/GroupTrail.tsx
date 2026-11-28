import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import type { GroupTrailStep } from '@/api/groupExpenses'
import { formatShortDateTime } from '@/lib/dates'
import type { memberNames } from '@/lib/groupExpenses'
import { cn } from '@/lib/utils'

/**
 * The trail of an expense or a payment, as in the design: every version and every reversal, newest
 * first, each with who made it and when, the version in effect marked as current. `describe` says
 * what a recorded or corrected version was; a reversal is the same for both.
 */
export function GroupTrail({
  trail,
  currentId,
  names,
  describe,
  note,
}: {
  trail: GroupTrailStep[]
  currentId: string | undefined
  names: ReturnType<typeof memberNames>
  describe: (step: GroupTrailStep) => string
  /** Why history never changes, under the steps. */
  note: string
}) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'
  const titleId = useId()
  const first = trail[0]?.entryId
  const steps = trail.toReversed()

  const what = (step: GroupTrailStep) =>
    step.action === 'REVERSED'
      ? step.reversesEntryId === first
        ? t('transactions.trail.originalReversed')
        : t('transactions.trail.correctionReversed')
      : describe(step)

  return (
    <section aria-labelledby={titleId} className="flex flex-col gap-3">
      <h3 id={titleId} className="font-semibold">
        {t('transactions.trail.title')}
      </h3>
      <ol>
        {steps.map((step, index) => {
          const latest = step.entryId === currentId
          return (
            <li
              key={step.entryId}
              className={cn(
                'relative grid grid-cols-[1.25rem_minmax(0,1fr)] gap-x-3',
                index < steps.length - 1 && 'pb-4.5',
              )}
            >
              {index < steps.length - 1 && (
                <span
                  aria-hidden
                  className="absolute top-4.5 bottom-0 left-[9px] w-0.5 bg-border"
                />
              )}
              <span
                aria-hidden
                className={cn(
                  'relative mt-1.25 size-3 justify-self-center rounded-full ring-3 ring-background',
                  latest
                    ? 'bg-primary'
                    : 'border-2 border-foreground/35 bg-background',
                )}
              />
              <div>
                <p className="font-medium">
                  {what(step)}
                  {latest && (
                    <span className="text-xs font-normal text-muted-foreground">
                      {' · '}
                      {t('transactions.trail.current')}
                    </span>
                  )}
                </p>
                <p className="mt-0.5 text-xs text-muted-foreground">
                  {t('groupExpenses.trail.meta', {
                    name: names.name(step.recordedBy),
                    when: formatShortDateTime(step.recordedAt, language),
                  })}
                </p>
              </div>
            </li>
          )
        })}
      </ol>
      <p className="rounded-[10px] bg-muted px-3 py-2.5 text-[13px] text-foreground/70">
        {note}
      </p>
    </section>
  )
}
