import { useTranslation } from 'react-i18next'
import { Badge } from '@/components/ui/badge'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'

type PlaceholderPageProps = {
  title: string
  description: string
  plannedFor: string
}

/** Page shell for features that are planned but not built yet. */
export function PlaceholderPage({
  title,
  description,
  plannedFor,
}: PlaceholderPageProps) {
  const { t } = useTranslation()

  return (
    <section className="flex flex-col gap-6">
      <h1 className="font-heading text-2xl font-semibold tracking-tight">
        {title}
      </h1>
      <Card>
        <CardHeader>
          <CardTitle>{t('placeholder.comingSoon')}</CardTitle>
          <CardDescription>{description}</CardDescription>
        </CardHeader>
        <CardContent>
          <Badge variant="outline">
            {t('placeholder.plannedFor', { milestone: plannedFor })}
          </Badge>
        </CardContent>
      </Card>
    </section>
  )
}
