import type { LucideProps } from 'lucide-react'
import { createElement } from 'react'
import { groupCategoryIcon } from '@/lib/groupExpenses'

/**
 * The icon of a group expense's category, the one its default category has. Drawn with
 * `createElement`, since the icon is looked up from the category: one of a fixed set of
 * components, never one made while rendering.
 */
export function GroupCategoryIcon({
  category,
  ...props
}: { category: string } & LucideProps) {
  return createElement(groupCategoryIcon(category), props)
}
