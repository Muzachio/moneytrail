import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { LanguageToggle } from '@/components/LanguageToggle'
import { Logo } from '@/components/Logo'

// Only facts the repository shows (docs/deploy.md, docs/architecture.md): change this page when
// they change.
const sections = [
  {
    title: 'privacy.stored.title',
    points: [
      'privacy.stored.google',
      'privacy.stored.records',
      'privacy.stored.device',
    ],
  },
  {
    title: 'privacy.where.title',
    points: [
      'privacy.where.servers',
      'privacy.where.pages',
      'privacy.where.telemetry',
    ],
  },
  {
    title: 'privacy.access.title',
    points: [
      'privacy.access.you',
      'privacy.access.groups',
      'privacy.access.maintainer',
    ],
  },
  { title: 'privacy.trackers.title', points: ['privacy.trackers.none'] },
  { title: 'privacy.rights.title', points: ['privacy.rights.ask'] },
] as const

/** How MoneyTrail handles people's data (#51). Open to everyone, with or without a session. */
export function PrivacyPage() {
  const { t } = useTranslation()

  return (
    <div className="flex min-h-svh flex-col">
      <header className="border-b bg-background">
        <div className="mx-auto flex max-w-2xl items-center px-4 py-3 sm:px-6">
          <Link
            to="/"
            className="flex items-center gap-2 font-heading text-lg font-semibold"
          >
            <Logo className="size-6" />
            MoneyTrail
          </Link>
        </div>
      </header>

      <main className="mx-auto flex w-full max-w-2xl flex-1 flex-col gap-8 px-4 py-8 sm:px-6">
        <div className="flex flex-col gap-2">
          <h1 className="font-heading text-2xl font-semibold tracking-tight">
            {t('privacy.title')}
          </h1>
          <p className="text-muted-foreground">{t('privacy.lead')}</p>
        </div>

        {sections.map((section) => (
          <section key={section.title} className="flex flex-col gap-2">
            <h2 className="font-heading text-lg font-semibold">
              {t(section.title)}
            </h2>
            <ul className="flex list-disc flex-col gap-1.5 pl-5">
              {section.points.map((point) => (
                <li key={point}>{t(point)}</li>
              ))}
            </ul>
          </section>
        ))}

        <p className="text-sm text-muted-foreground">{t('privacy.updated')}</p>
        <Link
          to="/"
          className="self-start text-sm font-medium underline-offset-4 hover:underline"
        >
          {t('privacy.back')}
        </Link>
      </main>

      {/* The bottom padding keeps the footer above the home indicator of phones that have one. */}
      <footer className="flex justify-center px-6 pt-4 pb-[calc(1.5rem+env(safe-area-inset-bottom))]">
        <LanguageToggle />
      </footer>
    </div>
  )
}
