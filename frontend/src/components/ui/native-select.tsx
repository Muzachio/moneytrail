import * as React from 'react'
import { cn } from 'cn'
import { ChevronDownIcon } from 'lucide-react'

/**
 * The browser's own `<select>`, styled like the other fields: phones open their native picker,
 * and keyboards and screen readers get the behaviour they already know.
 */
function NativeSelect({ className, ...props }: React.ComponentProps<'select'>) {
  return (
    <div
      data-slot="native-select-wrapper"
      className={cn(
        'group/native-select relative w-fit has-[select:disabled]:opacity-50',
        className,
      )}
    >
      <select
        data-slot="native-select"
        className="h-full min-h-9 w-full min-w-0 appearance-none truncate rounded-lg border border-input bg-background py-1 pr-9 pl-3 text-sm font-medium transition-colors outline-none select-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:pointer-events-none disabled:cursor-not-allowed aria-invalid:border-destructive aria-invalid:ring-3 aria-invalid:ring-destructive/20 dark:bg-input/30 dark:aria-invalid:border-destructive/50 dark:aria-invalid:ring-destructive/40"
        {...props}
      />
      <ChevronDownIcon
        aria-hidden
        className="pointer-events-none absolute top-1/2 right-3 size-4 -translate-y-1/2 text-muted-foreground select-none"
      />
    </div>
  )
}

function NativeSelectOption({ ...props }: React.ComponentProps<'option'>) {
  return <option data-slot="native-select-option" {...props} />
}

function NativeSelectOptGroup({ ...props }: React.ComponentProps<'optgroup'>) {
  return <optgroup data-slot="native-select-optgroup" {...props} />
}

export { NativeSelect, NativeSelectOptGroup, NativeSelectOption }
