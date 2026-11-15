import { type ReactNode, useId } from 'react'
import { Link, type To } from 'react-router'
import { cn } from '@/lib/utils'

/** A card of the summary: its title, a line under it, an action on the right, and its content. */
export function SummaryCard({
  title,
  description,
  action,
  className,
  children,
}: {
  title: string
  description?: string
  action?: ReactNode
  className?: string
  children: ReactNode
}) {
  const titleId = useId()
  return (
    <section
      aria-labelledby={titleId}
      className={cn(
        'flex min-w-0 flex-col gap-4 rounded-xl border bg-card p-5',
        className,
      )}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 id={titleId} className="text-[15px] font-semibold">
            {title}
          </h2>
          {description && (
            <p className="mt-0.5 text-[13px] text-muted-foreground">
              {description}
            </p>
          )}
        </div>
        {action}
      </div>
      {children}
    </section>
  )
}

/** "See transactions", "Manage": where a card's content goes on in full. */
export function CardLink({ to, children }: { to: To; children: string }) {
  return (
    <Link
      to={to}
      className="rounded-sm text-[13px] font-medium whitespace-nowrap underline underline-offset-4 outline-none hover:text-foreground/75 focus-visible:ring-3 focus-visible:ring-ring/50"
    >
      {children}
    </Link>
  )
}

/** A card's own empty state: one line where its list would be. */
export function CardEmpty({ children }: { children: string }) {
  return <p className="py-2 text-muted-foreground">{children}</p>
}
