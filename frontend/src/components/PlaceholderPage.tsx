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
  return (
    <section className="flex flex-col gap-6">
      <h1 className="font-heading text-2xl font-semibold tracking-tight">
        {title}
      </h1>
      <Card>
        <CardHeader>
          <CardTitle>Coming soon</CardTitle>
          <CardDescription>{description}</CardDescription>
        </CardHeader>
        <CardContent>
          <Badge variant="outline">Planned for {plannedFor}</Badge>
        </CardContent>
      </Card>
    </section>
  )
}
