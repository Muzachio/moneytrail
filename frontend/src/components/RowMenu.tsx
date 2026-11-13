import { Archive, EllipsisVertical, Pencil } from 'lucide-react'
import { useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'

/**
 * The "⋮" menu at the end of an account's or a category's row, with Rename and Archive as in the
 * design canvas.
 */
export function RowMenu({
  label,
  archiveHint,
  onRename,
  onArchive,
}: {
  /** Names the row, as "More actions for Wallet": every row has the same icon button. */
  label: string
  archiveHint: string
  /** Gets the menu's button, for the rename dialog to give focus back to when it closes. */
  onRename: (menuButton: HTMLElement | null) => void
  onArchive: () => void
}) {
  const { t } = useTranslation()
  const button = useRef<HTMLButtonElement>(null)
  return (
    // Not modal: the rename dialog opens as the menu closes, and a modal menu would still be
    // locking the page outside it at that moment.
    <DropdownMenu modal={false}>
      <DropdownMenuTrigger asChild>
        <Button
          ref={button}
          variant="ghost"
          size="icon-lg"
          aria-label={label}
          className="flex-none text-foreground/70"
        >
          <EllipsisVertical aria-hidden />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-58">
        <DropdownMenuItem onSelect={() => onRename(button.current)}>
          <Pencil aria-hidden />
          {t('rowMenu.rename')}
        </DropdownMenuItem>
        <DropdownMenuItem onSelect={onArchive} className="items-start">
          <Archive aria-hidden className="mt-0.5" />
          <span className="flex flex-col">
            {t('rowMenu.archive')}
            <span className="text-xs text-muted-foreground">{archiveHint}</span>
          </span>
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
