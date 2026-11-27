import { cn } from '@/lib/utils'

/**
 * Someone's Google photo, or their initials when they have none: the signed-in person in the
 * header, and the members of a group.
 */
export function PersonAvatar({
  person,
  className,
}: {
  person: { name: string; avatarUrl?: string }
  /** Size, and the ring that separates avatars drawn over each other. */
  className?: string
}) {
  const base = cn('size-9 flex-none rounded-full border bg-muted', className)
  if (person.avatarUrl) {
    // no-referrer: Google's image host does not need to know which page shows the photo.
    return (
      <img
        src={person.avatarUrl}
        alt=""
        referrerPolicy="no-referrer"
        className={cn(base, 'block object-cover')}
      />
    )
  }
  return (
    <span
      aria-hidden
      className={cn(
        base,
        'grid place-items-center text-[13px] font-semibold text-muted-foreground',
      )}
    >
      {initials(person.name)}
    </span>
  )
}

/** "Ana Teste" → "AT", "Rita" → "R". */
function initials(name: string) {
  const parts = name.trim().split(/\s+/)
  const letters =
    parts.length > 1 ? parts[0][0] + parts.at(-1)![0] : (parts[0][0] ?? '?')
  return letters.toUpperCase()
}

/** Avatars drawn over each other, as on a group's page and the join page. */
export function AvatarStack({
  people,
  className,
}: {
  people: readonly { name: string; avatarUrl?: string }[]
  className?: string
}) {
  return (
    <span aria-hidden className="flex flex-none ps-2">
      {people.map((person, index) => (
        <PersonAvatar
          // People can share a name; their order is stable.
          key={index}
          person={person}
          className={cn('-ms-2 ring-2 ring-background', className)}
        />
      ))}
    </span>
  )
}
