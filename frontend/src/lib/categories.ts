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
import type {
  Category,
  CategoryIcon,
  CategoryKey,
  CategoryKind,
} from '@/api/categories'

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

/**
 * The category names the app knows (`CategoryKey` in the spec), with the kind each is for. Their
 * translations are `categories.names.<key>` in each language; the English ones are the names the API
 * keeps, so keep them the same as the backend's `KnownName`. `satisfies` makes the build fail when
 * the spec adds or drops a key and this list does not.
 */
export const knownNames = {
  housing: 'EXPENSE',
  food: 'EXPENSE',
  transport: 'EXPENSE',
  bills: 'EXPENSE',
  shopping: 'EXPENSE',
  leisure: 'EXPENSE',
  health: 'EXPENSE',
  other: 'EXPENSE',
  salary: 'INCOME',
  refunds: 'INCOME',
  gifts: 'INCOME',
  'other-income': 'INCOME',
  groceries: 'EXPENSE',
  restaurants: 'EXPENSE',
  rent: 'EXPENSE',
  'condo-fees': 'EXPENSE',
  electricity: 'EXPENSE',
  water: 'EXPENSE',
  gas: 'EXPENSE',
  'phone-internet': 'EXPENSE',
  subscriptions: 'EXPENSE',
  fuel: 'EXPENSE',
  tolls: 'EXPENSE',
  insurance: 'EXPENSE',
  pharmacy: 'EXPENSE',
  'personal-care': 'EXPENSE',
  clothes: 'EXPENSE',
  gym: 'EXPENSE',
  education: 'EXPENSE',
  kids: 'EXPENSE',
  pets: 'EXPENSE',
  travel: 'EXPENSE',
  'gifts-given': 'EXPENSE',
  taxes: 'EXPENSE',
  freelance: 'INCOME',
  bonus: 'INCOME',
  interest: 'INCOME',
  dividends: 'INCOME',
  'holiday-allowance': 'INCOME',
  'christmas-allowance': 'INCOME',
  sales: 'INCOME',
  pension: 'INCOME',
} satisfies Record<CategoryKey, CategoryKind>

export const categoryKeys = Object.keys(knownNames) as CategoryKey[]

function isKnownKey(key: unknown): key is CategoryKey {
  return typeof key === 'string' && Object.hasOwn(knownNames, key)
}

/** A default category: it keeps its icon, so the person cannot pick another. */
export function isDefaultCategory(
  category: Pick<Category, 'default'>,
): boolean {
  return category.default
}

/** What names and draws a category: a `Category`, or one in a report (`CategorySpending`). */
type Named = Pick<Category, 'key' | 'name' | 'icon'>

/**
 * The icon the API gives the category: a default one's own, the one picked for the person's own.
 * A name this version of the app does not know yet (one added to the API later) shows as the plain
 * tag.
 */
export function categoryIcon(category: Pick<Named, 'icon'>): LucideIcon {
  return Object.hasOwn(categoryIcons, category.icon)
    ? categoryIcons[category.icon]
    : Tag
}

/**
 * What to call `category`: the name the person typed, or else the translation of its known name,
 * in the current language.
 *
 * A key this version of the app does not know yet (one added to the API later) shows as the key
 * itself, which is still readable, rather than as an empty row.
 */
export function categoryName(
  category: Pick<Named, 'key' | 'name'>,
  t: TFunction,
): string {
  if (category.name !== undefined) {
    return category.name
  }
  if (isKnownKey(category.key)) {
    return t(`categories.names.${category.key}`)
  }
  return category.key ?? ''
}

/** "Educação", "educacao" and " EDUCAÇÃO " compare equal: case, accents and spaces aside. */
function comparable(name: string): string {
  return name
    .normalize('NFD')
    .replace(/\p{M}/gu, '')
    .toLowerCase()
    .replace(/\s+/g, ' ')
    .trim()
}

/** How the API should call a category: by a known name, or by the name as typed. */
export type Naming = { key: CategoryKey } | { name: string }

/**
 * The known name of `kind` that `typed` is in the current language (that of `t`), or else `typed`
 * itself. Only the current language counts, so a name typed on purpose in another one stays as it
 * is: "Pets" typed in Portuguese is not the Portuguese "Animais".
 */
export function naming(
  kind: CategoryKind,
  typed: string,
  t: TFunction,
): Naming {
  const wanted = comparable(typed)
  const key = categoryKeys.find(
    (candidate) =>
      knownNames[candidate] === kind &&
      comparable(t(`categories.names.${candidate}`)) === wanted,
  )
  return key === undefined ? { name: typed.trim() } : { key }
}
