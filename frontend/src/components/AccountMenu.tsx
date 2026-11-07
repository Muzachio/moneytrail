import { LogOut } from 'lucide-react'
import { type CurrentUser, useSignOut } from '@/api/session'
import { Button } from '@/components/ui/button'

/** The signed-in person in the header: photo (or initials), name and sign-out. */
export function AccountMenu({ user }: { user: CurrentUser }) {
  const signOut = useSignOut()

  return (
    <div className="flex items-center gap-3">
      <Avatar user={user} />
      <span className="hidden text-sm font-medium sm:inline">{user.name}</span>
      <Button
        variant="ghost"
        size="sm"
        onClick={() => signOut.mutate()}
        disabled={signOut.isPending}
      >
        <LogOut aria-hidden />
        Sign out
      </Button>
    </div>
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
        className={className}
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
