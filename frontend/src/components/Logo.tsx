/**
 * MoneyTrail's logo, the same file as the favicon and the source of the home screen icons, so the
 * app shows the icon it was opened from. Decorative: the name always sits next to it or in the
 * heading.
 */
export function Logo({ className }: { className?: string }) {
  return <img src="/favicon.svg" alt="" className={className} />
}
