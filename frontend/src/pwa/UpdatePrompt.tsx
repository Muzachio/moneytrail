import { RefreshCw } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { useRegisterSW } from 'virtual:pwa-register/react'
import { Button } from '@/components/ui/button'

/**
 * Registers the service worker (vite-plugin-pwa) and, once a deploy has brought a new version,
 * offers it in a bar at the top of the page. Update reloads into it; without that, it starts the
 * next time the app opens.
 */
export function UpdatePrompt() {
  const { t } = useTranslation()
  const {
    needRefresh: [needRefresh],
    updateServiceWorker,
  } = useRegisterSW({
    onRegisteredSW(_url, registration) {
      // Browsers look for a new service worker when a page loads. An installed app is mostly
      // brought back from the background instead, so it looks again each time it comes back.
      document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible') {
          // Offline or unreachable: the next return tries again.
          registration?.update().catch(() => {})
        }
      })
    },
  })

  // The live region is always there, so screen readers announce the bar when it appears in it.
  return (
    <div role="status">
      {needRefresh && (
        <div className="flex flex-wrap items-center justify-center gap-x-3 gap-y-1 border-b bg-background px-4 py-2 text-sm">
          <span>{t('update.available')}</span>
          <Button size="sm" onClick={() => void updateServiceWorker()}>
            <RefreshCw aria-hidden />
            {t('update.reload')}
          </Button>
        </div>
      )}
    </div>
  )
}
