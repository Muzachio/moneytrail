import { type LucideIcon, Tag } from 'lucide-react'
import type { TFunction } from 'i18next'
import type { CategoryIcon } from '@/api/categories'
import type {
  GroupExpense,
  GroupExpenseCategory,
  NewGroupExpense,
  Settlement,
} from '@/api/groupExpenses'
import type { GroupMember } from '@/api/groups'
import { categoryIcons } from '@/lib/categories'
import type { Money } from '@/lib/money'

/**
 * A group expense's categories (`GroupExpenseCategory` in the spec), the default expense
 * categories, with the icon each has as a person's own category, in the order the dialog offers
 * them. Their names are `categories.names.<key>`, as for those categories. `satisfies` makes the
 * build fail when the spec adds or drops a key and this list does not.
 */
export const groupExpenseCategoryIcons = {
  housing: 'house',
  food: 'utensils',
  transport: 'tram-front',
  bills: 'receipt',
  shopping: 'shopping-bag',
  leisure: 'ticket',
  health: 'heart',
  other: 'tag',
} satisfies Record<GroupExpenseCategory, CategoryIcon>

export const groupExpenseCategories = Object.keys(
  groupExpenseCategoryIcons,
) as GroupExpenseCategory[]

/** The icon of a group expense's category; a key added to the API later shows as the tag. */
export function groupCategoryIcon(category: string): LucideIcon {
  return Object.hasOwn(groupExpenseCategoryIcons, category)
    ? categoryIcons[groupExpenseCategoryIcons[category as GroupExpenseCategory]]
    : Tag
}

/** The name of a group expense's category in the current language; an unknown key as itself. */
export function groupCategoryName(category: string, t: TFunction): string {
  return Object.hasOwn(groupExpenseCategoryIcons, category)
    ? t(`categories.names.${category as GroupExpenseCategory}`)
    : category
}

/**
 * How the group's pages call a person: "You" for the signed-in person, a member by name, and
 * someone who left, whom the group no longer lists, as a former member.
 */
export function memberNames(
  members: readonly GroupMember[],
  me: string | undefined,
  t: TFunction,
) {
  const byId = new Map(members.map((member) => [member.userId, member]))
  return {
    /** "You", "Rita", or "A former member": as the subject, or a label. */
    name: (userId: string) =>
      userId === me
        ? t('group.you')
        : (byId.get(userId)?.name ?? t('groupExpenses.formerMember')),
    /** "you" ("ti" in Portuguese) after a preposition: "Edited by you", "Editada por ti". */
    objectName: (userId: string) =>
      userId === me
        ? t('groupExpenses.youObject')
        : (byId.get(userId)?.name ?? t('groupExpenses.formerMember')),
    /** The member, for an avatar; someone who left has none. */
    member: (userId: string) => byId.get(userId),
  }
}

/** "Rita paid · by shares", "You paid · equally among 3": the second line of an expense's row. */
export function paidLine(
  expense: GroupExpense,
  names: ReturnType<typeof memberNames>,
  me: string | undefined,
  t: TFunction,
) {
  const payer =
    expense.paidBy === me
      ? t('groupExpenses.row.youPaid')
      : t('groupExpenses.row.paid', { name: names.name(expense.paidBy) })
  const split =
    expense.split.method === 'EQUALLY'
      ? t('groupExpenses.row.split.EQUALLY', {
          count: expense.split.members.length,
        })
      : t(`groupExpenses.row.split.${expense.split.method}`)
  return `${payer} · ${split}`
}

/** What an expense is to the signed-in person, for the second line of its row. */
export type MyPart =
  | { kind: 'lent'; amount: Money }
  | { kind: 'share'; amount: Money }
  | { kind: 'none' }

/**
 * "You lent €40.00" when the person paid for others too, "Your share €21.00" when they owe a part
 * of it, "Not involved" otherwise, as in the design canvas.
 */
export function myPart(expense: GroupExpense, me: string | undefined): MyPart {
  const mine =
    expense.split.members.find((member) => member.userId === me)?.share
      .amountMinor ?? 0
  const currency = expense.amount.currency
  if (expense.paidBy === me) {
    const lent = expense.amount.amountMinor - mine
    return lent > 0
      ? { kind: 'lent', amount: { amountMinor: lent, currency } }
      : { kind: 'share', amount: { amountMinor: mine, currency } }
  }
  return mine > 0
    ? { kind: 'share', amount: { amountMinor: mine, currency } }
    : { kind: 'none' }
}

/** The body that would record `expense` as it is: an edit starts from it. */
export function asNewGroupExpense(expense: GroupExpense): NewGroupExpense {
  return {
    amount: { ...expense.amount },
    occurredOn: expense.occurredOn,
    description: expense.description,
    category: expense.category,
    paidBy: expense.paidBy,
    split: {
      method: expense.split.method,
      members: expense.split.members.map(({ userId, value }) =>
        expense.split.method === 'EQUALLY' ? { userId } : { userId, value },
      ),
    },
  }
}

/** Whether two expenses would be recorded the same: an edit that changes nothing is not sent. */
export function sameGroupExpense(a: NewGroupExpense, b: NewGroupExpense) {
  // Field by field, so neither the order of the keys nor that of the members counts.
  const normal = (expense: NewGroupExpense) =>
    JSON.stringify([
      expense.amount.amountMinor,
      expense.amount.currency,
      expense.occurredOn,
      expense.description,
      expense.category,
      expense.paidBy,
      expense.split.method,
      expense.split.members
        .toSorted((x, y) =>
          x.userId < y.userId ? -1 : x.userId > y.userId ? 1 : 0,
        )
        .map(({ userId, value }) => [userId, value ?? null]),
    ])
  return normal(a) === normal(b)
}

/** "You paid Rita", "Rita paid you", "Pedro paid Rita": a payment's title in the list and detail. */
export function paymentTitle(
  payment: Settlement,
  names: ReturnType<typeof memberNames>,
  me: string | undefined,
  t: TFunction,
) {
  return payment.from === me
    ? t('groupExpenses.payment.youPaid', { to: names.name(payment.to) })
    : payment.to === me
      ? t('groupExpenses.payment.paidYou', { from: names.name(payment.from) })
      : t('groupExpenses.payment.paid', {
          from: names.name(payment.from),
          to: names.name(payment.to),
        })
}
