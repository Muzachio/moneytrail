import { LogOut, UserRound } from 'lucide-react'
import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import { type CurrentUser, useSignOut } from '@/api/session'
import { LanguageToggle } from '@/components/LanguageToggle'
import { Button } from '@/components/ui/button'
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui/popover'

/**
 * The signed-in person: a panel with their name, the language toggle and sign-out. In the header
 * their photo (or initials) opens it, as the "Your account" button in the design canvas; on a
 * phone the "You" tab does, at the end of the bottom tabs (`tab`).
 */
export function AccountMenu({
  user,
  tab = false,
  className,
}: {
  user: CurrentUser
  tab?: boolean
  className?: string
}) {
  const { t } = useTranslation()
  const signOut = useSignOut()
  const languageLabel = useId()

  return (
    <Popover>
      {tab ? (
        // Named by its visible label, "You", as the other tabs are.
        <PopoverTrigger className={className}>
          <UserRound aria-hidden className="size-5.5" />
          {t('accountMenu.tab')}
        </PopoverTrigger>
      ) : (
        <PopoverTrigger
          aria-label={t('accountMenu.label')}
          className="rounded-full outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
        >
          <Avatar user={user} />
        </PopoverTrigger>
      )}
      <PopoverContent
        align="end"
        side={tab ? 'top' : 'bottom'}
        className="w-72 gap-3 p-3"
      >
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

function Avatar({ user }: { user: CurrentUser }) {
  const className = 'size-9 rounded-full border bg-muted'
  if (user.avatarUrl) {
    // no-referrer: Google's image host does not need to know which page shows the photo.
    return (
      <img
        src={user.avatarUrl}
        alt=""
        referrerPolicy="no-referrer"
        className={`${className} block`}
      />
    )
  }
  return (
    <span
      aria-hidden
      className={`${className} grid place-items-center text-[13px] font-semibold text-muted-foreground`}
    >
      {initials(user.name)}
    </span>
  )
}

function initials(name: string) {
  const parts = name.trim().split(/\s+/)
  const letters =
    parts.length > 1 ? parts[0][0] + parts.at(-1)![0] : parts[0][0]
  return letters.toUpperCase()
}
