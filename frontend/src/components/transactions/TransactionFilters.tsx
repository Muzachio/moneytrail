import { ChevronLeft, ChevronRight, Search } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { Account } from '@/api/accounts'
import type { Category } from '@/api/categories'
import type { TransactionFilters } from '@/api/transactions'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  NativeSelect,
  NativeSelectOptGroup,
  NativeSelectOption,
} from '@/components/ui/native-select'
import { categoryName } from '@/lib/categories'
import { addMonths, formatMonth, type Month } from '@/lib/dates'
import { transactionTypes } from '@/lib/transactions'
import { cn } from '@/lib/utils'

/** The month shown, with the months before it; the months after the current one are empty. */
export function MonthSwitcher({
  month,
  latest,
  onChange,
}: {
  month: Month
  /** The current month: there is nothing to see after it. */
  latest: Month
  onChange: (month: Month) => void
}) {
  const { t, i18n } = useTranslation()
  return (
    <div className="flex items-center gap-0.5 rounded-[10px] border bg-card p-0.5">
      <Button
        variant="ghost"
        size="icon-lg"
        aria-label={t('transactions.month.previous')}
        onClick={() => onChange(addMonths(month, -1))}
      >
        <ChevronLeft aria-hidden />
      </Button>
      <span
        aria-live="polite"
        className="min-w-31 text-center font-medium first-letter:uppercase"
      >
        {formatMonth(month, i18n.resolvedLanguage ?? 'en')}
      </span>
      <Button
        variant="ghost"
        size="icon-lg"
        aria-label={t('transactions.month.next')}
        disabled={month >= latest}
        onClick={() => onChange(addMonths(month, 1))}
      >
        <ChevronRight aria-hidden />
      </Button>
    </div>
  )
}

/** How long the search waits after the last key before it asks the API. */
const SEARCH_DELAY_MS = 300

/**
 * The search, type, account and category filters of the design canvas. They live in the page's
 * address, so a reload, the back button or a shared link shows the same list.
 */
export function TransactionFilterBar({
  filters,
  accounts,
  categories,
  onChange,
}: {
  filters: TransactionFilters
  accounts: Account[]
  categories: Category[]
  onChange: (change: Partial<Omit<TransactionFilters, 'month'>>) => void
}) {
  const { t } = useTranslation()
  const active = accounts.filter((account) => !account.archived)
  const archivedAccounts = accounts.filter((account) => account.archived)
  const ofKind = (kind: Category['kind']) =>
    categories.filter(
      (category) => category.kind === kind && !category.archived,
    )
  const archivedCategories = categories.filter((category) => category.archived)

  return (
    <div className="flex flex-wrap items-center gap-2">
      <SearchBox
        value={filters.q ?? ''}
        onSearch={(q) => onChange({ q: q === '' ? undefined : q })}
      />
      <div
        role="group"
        aria-label={t('transactions.filters.type')}
        // Two by two on a phone: in one row, "Transferências" would not fit in 375px.
        className="grid w-full grid-cols-2 gap-0.5 rounded-[10px] bg-muted p-0.5 min-[480px]:inline-flex min-[480px]:w-auto"
      >
        {[undefined, ...transactionTypes].map((type) => (
          <button
            key={type ?? 'ALL'}
            type="button"
            aria-pressed={filters.type === type}
            onClick={() => onChange({ type })}
            className={cn(
              'h-8 rounded-lg px-3 font-medium text-muted-foreground transition-colors outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50',
              filters.type === type &&
                'bg-background text-foreground shadow-xs',
            )}
          >
            {t(`transactions.filters.types.${type ?? 'ALL'}`)}
          </button>
        ))}
      </div>
      <NativeSelect
        aria-label={t('transactions.filters.account')}
        value={filters.accountId ?? ''}
        onChange={(event) =>
          onChange({ accountId: event.target.value || undefined })
        }
        // Side by side when they fit, each the width of the screen on a phone.
        className="h-9 max-w-full min-w-40 flex-1 sm:flex-none"
      >
        <NativeSelectOption value="">
          {t('transactions.filters.allAccounts')}
        </NativeSelectOption>
        {active.map((account) => (
          <NativeSelectOption key={account.id} value={account.id}>
            {account.name}
          </NativeSelectOption>
        ))}
        {archivedAccounts.length > 0 && (
          <NativeSelectOptGroup label={t('transactions.filters.archived')}>
            {archivedAccounts.map((account) => (
              <NativeSelectOption key={account.id} value={account.id}>
                {account.name}
              </NativeSelectOption>
            ))}
          </NativeSelectOptGroup>
        )}
      </NativeSelect>
      <NativeSelect
        aria-label={t('transactions.filters.category')}
        value={filters.categoryId ?? ''}
        onChange={(event) =>
          onChange({ categoryId: event.target.value || undefined })
        }
        // Side by side when they fit, each the width of the screen on a phone.
        className="h-9 max-w-full min-w-40 flex-1 sm:flex-none"
      >
        <NativeSelectOption value="">
          {t('transactions.filters.allCategories')}
        </NativeSelectOption>
        {(['EXPENSE', 'INCOME'] as const).map((kind) => (
          <NativeSelectOptGroup
            key={kind}
            label={t(`categories.kinds.${kind}`)}
          >
            {ofKind(kind).map((category) => (
              <NativeSelectOption key={category.id} value={category.id}>
                {categoryName(category, t)}
              </NativeSelectOption>
            ))}
          </NativeSelectOptGroup>
        ))}
        {archivedCategories.length > 0 && (
          <NativeSelectOptGroup label={t('transactions.filters.archived')}>
            {archivedCategories.map((category) => (
              <NativeSelectOption key={category.id} value={category.id}>
                {categoryName(category, t)}
              </NativeSelectOption>
            ))}
          </NativeSelectOptGroup>
        )}
      </NativeSelect>
    </div>
  )
}

function SearchBox({
  value,
  onSearch,
}: {
  value: string
  onSearch: (q: string) => void
}) {
  const { t } = useTranslation()
  const [text, setText] = useState(value)
  // The search changed elsewhere ("Clear filters", the back button): the field follows it.
  const [searched, setSearched] = useState(value)
  if (value !== searched) {
    setSearched(value)
    if (value !== text.trim()) {
      setText(value)
    }
  }
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined)
  useEffect(() => () => clearTimeout(timer.current), [])

  const search = (q: string) => {
    clearTimeout(timer.current)
    if (q.trim() !== value) {
      onSearch(q.trim())
    }
  }

  return (
    <form
      role="search"
      className="relative min-w-50 flex-[1_1_15rem]"
      onSubmit={(event) => {
        // Enter searches at once, without waiting for the pause.
        event.preventDefault()
        search(text)
      }}
    >
      <Search
        aria-hidden
        className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground"
      />
      <Input
        type="search"
        aria-label={t('transactions.filters.search')}
        placeholder={t('transactions.filters.searchPlaceholder')}
        value={text}
        maxLength={100}
        onChange={(event) => {
          const next = event.target.value
          setText(next)
          clearTimeout(timer.current)
          // Asks once the person pauses, not at every key.
          timer.current = setTimeout(() => search(next), SEARCH_DELAY_MS)
        }}
        className="h-9 bg-background pl-9"
      />
    </form>
  )
}
