import { Link } from 'react-router'
import { Button } from '@/components/ui/button'

export function NotFoundPage() {
  return (
    <section className="flex flex-col items-start gap-4">
      <h1 className="font-heading text-2xl font-semibold tracking-tight">
        Page not found
      </h1>
      <p className="text-muted-foreground">This page does not exist (yet).</p>
      <Button asChild variant="outline">
        <Link to="/">Back to the summary</Link>
      </Button>
    </section>
  )
}
