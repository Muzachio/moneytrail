import { useTranslation } from 'react-i18next'
import { PlaceholderPage } from '@/components/PlaceholderPage'

export function TransactionsPage() {
  const { t } = useTranslation()

  return (
    <PlaceholderPage
      title={t('transactions.title')}
      description={t('transactions.description')}
      plannedFor={t('placeholder.m1')}
    />
  )
}
