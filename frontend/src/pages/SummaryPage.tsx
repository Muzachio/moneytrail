import { useTranslation } from 'react-i18next'
import { PlaceholderPage } from '@/components/PlaceholderPage'

export function SummaryPage() {
  const { t } = useTranslation()

  return (
    <PlaceholderPage
      title={t('summary.title')}
      description={t('summary.description')}
      plannedFor={t('placeholder.m1')}
    />
  )
}
