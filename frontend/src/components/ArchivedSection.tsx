import { ChevronRight } from 'lucide-react'
import { type ReactNode, useId, useState } from 'react'
import { cn } from '@/lib/utils'

/**
 * A disclosure for archived items, closed at first: archived accounts and categories are out of
 * the way, but one click brings them back to restore one.
 */
export function ArchivedSection({
  title,
  headingLevel,
  children,
}: {
  /** With the count, as "Archived accounts (1)". */
  title: string
  /** Its place in the page's outline: a heading level below the section it belongs to. */
  headingLevel: 2 | 3
  children: ReactNode
}) {
  const [open, setOpen] = useState(false)
  const contentId = useId()
  const Heading = headingLevel === 2 ? 'h2' : 'h3'

  return (
    <section className="flex flex-col gap-2.5">
      <Heading>
        <button
          type="button"
          aria-expanded={open}
          aria-controls={contentId}
          onClick={() => setOpen(!open)}
          className="inline-flex h-9 items-center gap-2 rounded-lg px-2 font-medium text-foreground/75 outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50"
        >
          <ChevronRight
            aria-hidden
            className={cn('size-4 transition-transform', open && 'rotate-90')}
          />
          {title}
        </button>
      </Heading>
      <div id={contentId} hidden={!open}>
        {children}
      </div>
    </section>
  )
}
