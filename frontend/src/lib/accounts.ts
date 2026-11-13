import {
  Coins,
  CreditCard,
  Landmark,
  type LucideIcon,
  PiggyBank,
} from 'lucide-react'
import type { TFunction } from 'i18next'
import type { Account, AccountType } from '@/api/accounts'
import { type Money, sumMoney } from '@/lib/money'

/** The only currency in M1 (`CurrencyCode` in the spec). */
export const CURRENCY = 'EUR'

/** In the order the add-account dialog offers them, as in the design canvas. */
export const accountTypes = [
  'CHECKING',
  'SAVINGS',
  'CREDIT_CARD',
  'CASH',
] as const satisfies readonly AccountType[]

/** The design canvas' icon for each type of account. */
export const accountTypeIcons: Record<AccountType, LucideIcon> = {
  CHECKING: Landmark,
  SAVINGS: PiggyBank,
  CREDIT_CARD: CreditCard,
  CASH: Coins,
}

/**
 * Whether the account holds money the person owes. A credit card is a liability in the ledger and
 * its balance is negative; the others are assets.
 */
export function isLiability(type: AccountType) {
  return type === 'CREDIT_CARD'
}

/** "Savings", or "Credit card · what you owe": its negative balance is a debt, not a loss. */
export function accountKind(account: Account, t: TFunction) {
  const type = t(`accounts.types.${account.type}`)
  return isLiability(account.type) ? t('accounts.owed', { type }) : type
}

export type AccountTotals = {
  netWorth: Money
  assets: Money
  liabilities: Money
  changeThisMonth: Money
}

/**
 * What the person owns and owes across every account. Archived accounts count too: archiving hides
 * an account from the lists, but money left in it is still theirs.
 */
export function accountTotals(accounts: readonly Account[]): AccountTotals {
  const assets = accounts.filter((account) => !isLiability(account.type))
  const liabilities = accounts.filter((account) => isLiability(account.type))
  return {
    netWorth: balanceOf(accounts),
    assets: balanceOf(assets),
    liabilities: balanceOf(liabilities),
    changeThisMonth: sumMoney(
      CURRENCY,
      accounts.map((account) => account.changeThisMonth),
    ),
  }
}

/** The sum of the balances of `accounts`. */
export function balanceOf(accounts: readonly Account[]): Money {
  return sumMoney(
    CURRENCY,
    accounts.map((account) => account.balance),
  )
}
