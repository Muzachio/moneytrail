import { CircleAlert } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/button'

/** Shown while a list is on its way. */
export function Loading({ children }: { children: string }) {
  return (
    <p role="status" className="py-6 text-muted-foreground">
      {children}
    </p>
  )
}

/** Shown when a list could not be loaded, with a way to ask again. */
export function LoadFailed({
  children,
  onRetry,
}: {
  children: string
  onRetry: () => void
}) {
  const { t } = useTranslation()
  return (
    <div
      role="alert"
      className="flex flex-wrap items-center gap-3 rounded-xl border bg-card p-4"
    >
      <CircleAlert aria-hidden className="size-4 flex-none text-destructive" />
      <span className="min-w-0 flex-1">{children}</span>
      <Button variant="outline" size="lg" onClick={onRetry}>
        {t('common.tryAgain')}
      </Button>
    </div>
  )
}
