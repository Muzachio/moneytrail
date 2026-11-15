import { Share, SquarePlus } from 'lucide-react'
import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import { Logo } from '@/components/Logo'
import { Button } from '@/components/ui/button'
import { dismissInstall, install, useInstallWay } from './install'

/**
 * The offer to install MoneyTrail on the device, at the top of every page until it is installed or
 * dismissed. Chromium browsers open their own dialog from the Install button; an iPhone or iPad
 * can only add a page to the home screen from the share menu, so it gets the steps instead.
 */
export function InstallPrompt() {
  const { t } = useTranslation()
  const way = useInstallWay()
  const titleId = useId()

  if (way === null) {
    return null
  }

  return (
    <section
      aria-labelledby={titleId}
      className="mb-6 flex items-start gap-3 rounded-xl border bg-card p-4"
    >
      {/* The icon it will have on the home screen. */}
      <Logo className="size-10 flex-none" />
      <div className="flex min-w-0 flex-1 flex-col gap-3">
        <div className="flex flex-col gap-1">
          <h2 id={titleId} className="font-heading font-semibold">
            {t('install.title')}
          </h2>
          {way === 'prompt' ? (
            <p className="text-sm text-muted-foreground">
              {t('install.description')}
            </p>
          ) : (
            <ol className="flex flex-col gap-1.5 text-sm text-muted-foreground">
              <li className="flex items-start gap-2">
                <Share aria-hidden className="mt-0.5 size-4 flex-none" />
                <span>{t('install.ios.share')}</span>
              </li>
              <li className="flex items-start gap-2">
                <SquarePlus aria-hidden className="mt-0.5 size-4 flex-none" />
                <span>{t('install.ios.addToHomeScreen')}</span>
              </li>
              <li className="flex items-start gap-2 pl-6">
                {t('install.ios.add')}
              </li>
            </ol>
          )}
        </div>
        <div className="flex flex-wrap gap-2">
          {way === 'prompt' && (
            <Button onClick={() => void install()}>
              {t('install.install')}
            </Button>
          )}
          <Button
            variant={way === 'prompt' ? 'ghost' : 'outline'}
            onClick={() => dismissInstall()}
          >
            {t('install.notNow')}
          </Button>
        </div>
      </div>
    </section>
  )
}
