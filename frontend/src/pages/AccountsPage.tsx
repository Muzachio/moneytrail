import { useTranslation } from 'react-i18next'
import { PlaceholderPage } from '@/components/PlaceholderPage'

export function AccountsPage() {
  const { t } = useTranslation()

  return (
    <PlaceholderPage
      title={t('accounts.title')}
      description={t('accounts.description')}
      plannedFor={t('placeholder.m1')}
    />
  )
}
