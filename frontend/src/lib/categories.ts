import {
  Baby,
  Banknote,
  Briefcase,
  Car,
  Coffee,
  Dumbbell,
  Fuel,
  Gift,
  GraduationCap,
  Heart,
  House,
  type LucideIcon,
  PawPrint,
  PiggyBank,
  Plane,
  Receipt,
  RotateCcw,
  Shirt,
  ShoppingBag,
  Smartphone,
  Tag,
  Ticket,
  TramFront,
  Utensils,
  Wrench,
} from 'lucide-react'
import type { TFunction } from 'i18next'
import type { Category, CategoryIcon } from '@/api/categories'

/**
 * Every icon a category can have (`CategoryIcon` in the spec), in the order the picker offers them:
 * the default categories' ones (design canvas), then the rest. `satisfies` makes the build fail
 * when the spec adds or drops a name and this list does not.
 */
export const categoryIcons = {
  house: House,
  utensils: Utensils,
  'tram-front': TramFront,
  receipt: Receipt,
  'shopping-bag': ShoppingBag,
  ticket: Ticket,
  heart: Heart,
  tag: Tag,
  banknote: Banknote,
  'rotate-ccw': RotateCcw,
  gift: Gift,
  car: Car,
  fuel: Fuel,
  plane: Plane,
  coffee: Coffee,
  shirt: Shirt,
  smartphone: Smartphone,
  'graduation-cap': GraduationCap,
  baby: Baby,
  'paw-print': PawPrint,
  dumbbell: Dumbbell,
  wrench: Wrench,
  'piggy-bank': PiggyBank,
  briefcase: Briefcase,
} satisfies Record<CategoryIcon, LucideIcon>

export const categoryIconNames = Object.keys(categoryIcons) as CategoryIcon[]

/** The icon a category of the person's own starts with, as the API gives it one left out. */
export const defaultIcon: CategoryIcon = 'tag'

/** The default categories every person starts with (`Category.key` in the spec). */
const defaultCategoryKeys = [
  'housing',
  'food',
  'transport',
  'bills',
  'shopping',
  'leisure',
  'health',
  'other',
  'salary',
  'refunds',
  'gifts',
  'other-income',
] as const

export type DefaultCategoryKey = (typeof defaultCategoryKeys)[number]

function isDefaultCategoryKey(key: unknown): key is DefaultCategoryKey {
  return defaultCategoryKeys.includes(key as DefaultCategoryKey)
}

/** A default category: it keeps the icon of its key, so the person cannot pick another. */
export function isDefaultCategory(category: Pick<Category, 'key'>): boolean {
  return category.key !== undefined
}

/** What names and draws a category: a `Category`, or one in a report (`CategorySpending`). */
type Named = Pick<Category, 'key' | 'name' | 'icon'>

/**
 * The icon the API gives the category: its key's for a default one, the one picked for the
 * person's own. A name this version of the app does not know yet (one added to the API later)
 * shows as the plain tag.
 */
export function categoryIcon(category: Pick<Named, 'icon'>): LucideIcon {
  return Object.hasOwn(categoryIcons, category.icon)
    ? categoryIcons[category.icon]
    : Tag
}

/**
 * What to call `category`: the name the person gave it, or else the translation of its key.
 *
 * A key this version of the app does not know yet (a default added to the API later) shows as the
 * key itself, which is still readable, rather than as an empty row.
 */
export function categoryName(
  category: Pick<Named, 'key' | 'name'>,
  t: TFunction,
): string {
  if (category.name !== undefined) {
    return category.name
  }
  if (isDefaultCategoryKey(category.key)) {
    return t(`categories.defaults.${category.key}`)
  }
  return category.key ?? ''
}
