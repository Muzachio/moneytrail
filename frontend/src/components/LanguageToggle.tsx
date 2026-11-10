import type { ComponentProps } from 'react'
import { useTranslation } from 'react-i18next'
import { changeLanguage } from '@/i18n/i18n'
import { languageNames, languages } from '@/i18n/language'
import { cn } from '@/lib/utils'

/** The English / Português toggle from the design canvas. */
export function LanguageToggle({ className, ...props }: ComponentProps<'div'>) {
  const { t, i18n } = useTranslation()

  return (
    <div
      role="group"
      aria-label={props['aria-labelledby'] ? undefined : t('language.label')}
      className={cn(
        // 6% of the text colour: the canvas's #F0F0F0 on white, still visible on the tinted page.
        'inline-flex gap-0.5 rounded-md bg-foreground/6 p-0.5 text-[13px]',
        className,
      )}
      {...props}
    >
      {languages.map((language) => {
        const active = i18n.resolvedLanguage === language
        return (
          <button
            key={language}
            type="button"
            // Lets a screen reader say "Português" with a Portuguese voice.
            lang={language}
            aria-pressed={active}
            onClick={() => void changeLanguage(language)}
            className={cn(
              'h-7 rounded-sm px-2.5 font-medium transition-colors outline-none focus-visible:ring-3 focus-visible:ring-ring/50',
              active
                ? 'bg-background text-foreground shadow-xs'
                : 'text-muted-foreground hover:text-foreground',
            )}
          >
            {languageNames[language]}
          </button>
        )
      })}
    </div>
  )
}
