import {
  Coins,
  CreditCard,
  Landmark,
  type LucideIcon,
  PiggyBank,
  TrendingUp,
} from 'lucide-react'
import type { TFunction } from 'i18next'
import type { Account, AccountType } from '@/api/accounts'
import { type Money, sumMoney } from '@/lib/money'

/** The only currency in M1 (`CurrencyCode` in the spec). */
export const CURRENCY = 'EUR'

/**
 * In the order the add-account dialog offers them: the design canvas' four, then investments
 * (#121), which came later.
 */
export const accountTypes = [
  'CHECKING',
  'SAVINGS',
  'CREDIT_CARD',
  'CASH',
  'INVESTMENT',
] as const satisfies readonly AccountType[]

/**
 * The types an account holding money the person has can change to: one asset can become another,
 * but a credit card keeps its type, since the same balance would then mean money owed.
 */
export const assetTypes = accountTypes.filter((type) => !isLiability(type))

/** The design canvas' icon for each type of account, and a rising line for investments. */
export const accountTypeIcons: Record<AccountType, LucideIcon> = {
  CHECKING: Landmark,
  SAVINGS: PiggyBank,
  CREDIT_CARD: CreditCard,
  CASH: Coins,
  INVESTMENT: TrendingUp,
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
