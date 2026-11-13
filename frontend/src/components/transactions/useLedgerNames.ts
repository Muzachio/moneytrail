import { ArrowLeftRight, type LucideIcon, Tag } from 'lucide-react'
import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { type Account, useAccounts } from '@/api/accounts'
import { type Category, useCategories } from '@/api/categories'
import type { Transaction } from '@/api/transactions'
import { categoryIcon, categoryName } from '@/lib/categories'

/** How a transaction reads in a list: its icon, its category and the account(s) it moved. */
export type TransactionLabels = {
  icon: LucideIcon
  category: string
  account: string
}

/**
 * The names behind the ids a transaction carries. The API sends ids only, so the accounts and the
 * categories are looked up in their own lists, archived ones included: an old transaction may use
 * an account that is archived now.
 */
export function useLedgerNames() {
  const { t } = useTranslation()
  const accounts = useAccounts()
  const categories = useCategories()

  const accountsById = useMemo(
    () => new Map(accounts.data?.map((account) => [account.id, account])),
    [accounts.data],
  )
  const categoriesById = useMemo(
    () => new Map(categories.data?.map((category) => [category.id, category])),
    [categories.data],
  )

  const account = (id: string | undefined): Account | undefined =>
    id === undefined ? undefined : accountsById.get(id)
  const category = (id: string | undefined): Category | undefined =>
    id === undefined ? undefined : categoriesById.get(id)
  const accountName = (id: string | undefined) => account(id)?.name ?? '—'
  const nameOfCategory = (id: string | undefined) => {
    const found = category(id)
    return found === undefined ? '—' : categoryName(found, t)
  }

  /** "Food" and "Everyday checking"; for a transfer "Transfer" and "Everyday checking → Wallet". */
  const labels = (transaction: Transaction): TransactionLabels => {
    if (transaction.type === 'TRANSFER') {
      return {
        icon: ArrowLeftRight,
        category: t('transactions.transfer'),
        account: t('transactions.fromTo', {
          from: accountName(transaction.fromAccountId),
          to: accountName(transaction.toAccountId),
        }),
      }
    }
    const found = category(transaction.categoryId)
    return {
      icon: found === undefined ? Tag : categoryIcon(found),
      category: nameOfCategory(transaction.categoryId),
      account: accountName(transaction.accountId),
    }
  }

  return {
    accounts,
    categories,
    isPending: accounts.isPending || categories.isPending,
    isError: accounts.isError || categories.isError,
    refetch: () => {
      void accounts.refetch()
      void categories.refetch()
    },
    account,
    category,
    accountName,
    categoryName: nameOfCategory,
    labels,
  }
}
