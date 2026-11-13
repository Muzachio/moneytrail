import {
  Banknote,
  Gift,
  Heart,
  House,
  type LucideIcon,
  Receipt,
  RotateCcw,
  ShoppingBag,
  Tag,
  Ticket,
  TramFront,
  Utensils,
} from 'lucide-react'
import type { TFunction } from 'i18next'
import type { Category } from '@/api/categories'

/**
 * The default categories every person starts with (`Category.key` in the spec), each with the
 * design canvas' icon. The API sends the key, never a name, until the person renames one, so
 * everyone sees them in their own language.
 */
const defaultCategoryIcons = {
  housing: House,
  food: Utensils,
  transport: TramFront,
  bills: Receipt,
  shopping: ShoppingBag,
  leisure: Ticket,
  health: Heart,
  other: Tag,
  salary: Banknote,
  refunds: RotateCcw,
  gifts: Gift,
  'other-income': Tag,
} satisfies Record<string, LucideIcon>

export type DefaultCategoryKey = keyof typeof defaultCategoryIcons

function isDefaultCategoryKey(key: unknown): key is DefaultCategoryKey {
  return typeof key === 'string' && Object.hasOwn(defaultCategoryIcons, key)
}

/** Its key's icon for a default category; a plain tag for the person's own ones. */
export function categoryIcon(category: Category): LucideIcon {
  return isDefaultCategoryKey(category.key)
    ? defaultCategoryIcons[category.key]
    : Tag
}

/**
 * What to call `category`: the name the person gave it, or else the translation of its key.
 *
 * A key this version of the app does not know yet (a default added to the API later) shows as the
 * key itself, which is still readable, rather than as an empty row.
 */
export function categoryName(category: Category, t: TFunction): string {
  if (category.name !== undefined) {
    return category.name
  }
  if (isDefaultCategoryKey(category.key)) {
    return t(`categories.defaults.${category.key}`)
  }
  return category.key ?? ''
}
