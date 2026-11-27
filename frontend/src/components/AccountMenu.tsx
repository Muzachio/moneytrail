import { LogOut } from 'lucide-react'
import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import { type CurrentUser, useSignOut } from '@/api/session'
import { LanguageToggle } from '@/components/LanguageToggle'
import { PersonAvatar } from '@/components/PersonAvatar'
import { Button } from '@/components/ui/button'
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui/popover'

/**
 * The signed-in person: a panel with their name, the language toggle and sign-out, opened by their
 * photo (or initials) in the header, as the "Your account" button in the design canvas. On a phone
 * too: the Groups tab took the place the "You" tab had at the end of the bottom tabs (#91).
 */
export function AccountMenu({ user }: { user: CurrentUser }) {
  const { t } = useTranslation()
  const signOut = useSignOut()
  const languageLabel = useId()

  return (
    <Popover>
      <PopoverTrigger
        aria-label={t('accountMenu.label')}
        // 44 px of target on a phone, around the 36 px avatar.
        className="grid size-11 place-items-center rounded-full outline-none focus-visible:ring-3 focus-visible:ring-ring/50 sm:size-9"
      >
        <PersonAvatar person={user} />
      </PopoverTrigger>
      <PopoverContent align="end" side="bottom" className="w-72 gap-3 p-3">
        <div className="flex min-w-0 flex-col gap-0.5">
          <p className="truncate font-medium">{user.name}</p>
          <p className="truncate text-muted-foreground">{user.email}</p>
        </div>
        <div className="flex items-center justify-between gap-3 border-t pt-3">
          <span id={languageLabel} className="text-muted-foreground">
            {t('language.label')}
          </span>
          <LanguageToggle aria-labelledby={languageLabel} />
        </div>
        <Button
          variant="ghost"
          className="justify-start"
          onClick={() => signOut.mutate()}
          disabled={signOut.isPending}
        >
          <LogOut aria-hidden />
          {t('accountMenu.signOut')}
        </Button>
      </PopoverContent>
    </Popover>
  )
}
