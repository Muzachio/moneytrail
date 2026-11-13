import { Info, Landmark, Plus } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { type Account, useAccounts, useUpdateAccount } from '@/api/accounts'
import { AccountGroup } from '@/components/accounts/AccountGroup'
import { AddAccountDialog } from '@/components/accounts/AddAccountDialog'
import { NetWorthCard } from '@/components/accounts/NetWorthCard'
import { ArchivedSection } from '@/components/ArchivedSection'
import { CategoriesSection } from '@/components/categories/CategoriesSection'
import { NameDialog } from '@/components/NameDialog'
import { LoadFailed, Loading } from '@/components/QueryState'
import { Button } from '@/components/ui/button'
import {
  accountKind,
  accountTotals,
  accountTypeIcons,
  isLiability,
} from '@/lib/accounts'
import { formatDate } from '@/lib/dates'
import { apiErrorMessage } from '@/lib/forms'
import { formatMoney } from '@/lib/money'

/** The Accounts page of the design canvas, with the categories below the accounts. */
export function AccountsPage() {
  const { t } = useTranslation()
  const [adding, setAdding] = useState(false)
  // There are two Add account buttons: the header's, and the empty state's.
  const [addOpener, setAddOpener] = useState<HTMLElement | null>(null)
  const add = (opener: HTMLElement) => {
    setAddOpener(opener)
    setAdding(true)
  }

  return (
    <div className="flex flex-col gap-10">
      <section className="flex flex-col gap-6">
        <div className="flex flex-wrap items-end justify-between gap-4">
          <div>
            <h1 className="font-heading text-[1.75rem] leading-tight font-semibold tracking-tight">
              {t('accounts.title')}
            </h1>
            <p className="mt-1 text-muted-foreground">{t('accounts.lead')}</p>
          </div>
          <Button
            variant="outline"
            size="lg"
            onClick={(event) => add(event.currentTarget)}
          >
            <Plus aria-hidden />
            {t('accounts.add')}
          </Button>
        </div>
        <Accounts onAdd={add} />
      </section>
      <CategoriesSection />
      <AddAccountDialog
        open={adding}
        onOpenChange={setAdding}
        returnFocusTo={addOpener}
      />
    </div>
  )
}

function Accounts({ onAdd }: { onAdd: (opener: HTMLElement) => void }) {
  const { t } = useTranslation()
  const accounts = useAccounts()
  const rename = useUpdateAccount()
  const archive = useUpdateAccount()
  const [renaming, setRenaming] = useState<Account | null>(null)
  const [renameOpener, setRenameOpener] = useState<HTMLElement | null>(null)

  if (accounts.isPending) {
    return <Loading>{t('accounts.loading')}</Loading>
  }
  if (accounts.isError) {
    return (
      <LoadFailed onRetry={() => void accounts.refetch()}>
        {t('accounts.loadFailed')}
      </LoadFailed>
    )
  }
  if (accounts.data.length === 0) {
    return <NoAccounts onAdd={onAdd} />
  }

  const active = accounts.data.filter((account) => !account.archived)
  const archived = accounts.data.filter((account) => account.archived)
  const groups = [
    {
      title: t('accounts.groups.cashAndBank'),
      accounts: active.filter((account) => !isLiability(account.type)),
    },
    {
      title: t('accounts.groups.creditCards'),
      accounts: active.filter((account) => isLiability(account.type)),
    },
  ].filter((group) => group.accounts.length > 0)

  const setArchived = (account: Account, archived: boolean) =>
    archive.mutate({ id: account.id, archived })

  return (
    <>
      <NetWorthCard totals={accountTotals(accounts.data)} />
      {archive.isError && (
        <p
          role="alert"
          className="rounded-lg bg-destructive/10 px-3 py-2.5 text-destructive"
        >
          {t(
            archive.variables.archived
              ? 'accounts.archiveFailed'
              : 'accounts.restoreFailed',
            {
              name:
                accounts.data.find(({ id }) => id === archive.variables.id)
                  ?.name ?? '',
            },
          )}{' '}
          {apiErrorMessage(archive.error, t)}
        </p>
      )}
      {groups.map((group) => (
        <AccountGroup
          key={group.title}
          title={group.title}
          accounts={group.accounts}
          onRename={(account, menuButton) => {
            setRenameOpener(menuButton)
            setRenaming(account)
          }}
          onArchive={(account) => setArchived(account, true)}
        />
      ))}
      {active.length === 0 && (
        <p className="rounded-xl border border-dashed bg-card p-4 text-muted-foreground">
          {t('accounts.allArchived')}
        </p>
      )}
      {archived.length > 0 && (
        <ArchivedSection
          headingLevel={2}
          title={t('accounts.archived.title', { count: archived.length })}
        >
          <ArchivedAccounts
            accounts={archived}
            restoring={archive.isPending ? archive.variables.id : undefined}
            onRestore={(account) => setArchived(account, false)}
          />
        </ArchivedSection>
      )}
      <p className="flex items-start gap-2 text-[13px] text-muted-foreground">
        <Info aria-hidden className="mt-0.5 size-4 flex-none" />
        {t('accounts.note')}
      </p>
      <NameDialog
        open={renaming !== null}
        onOpenChange={(open) => !open && setRenaming(null)}
        title={t('accounts.renameTitle')}
        submitLabel={t('common.save')}
        initialName={renaming?.name}
        onSave={(name) => rename.mutateAsync({ id: renaming?.id ?? '', name })}
        returnFocusTo={renameOpener}
      />
    </>
  )
}

function ArchivedAccounts({
  accounts,
  restoring,
  onRestore,
}: {
  accounts: Account[]
  restoring: string | undefined
  onRestore: (account: Account) => void
}) {
  const { t, i18n } = useTranslation()
  const language = i18n.resolvedLanguage ?? 'en'

  return (
    <ul className="divide-y divide-border/60 rounded-xl border bg-card">
      {accounts.map((account) => {
        const Icon = accountTypeIcons[account.type]
        return (
          <li
            key={account.id}
            className="flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3"
          >
            <span className="grid size-9 flex-none place-items-center rounded-[10px] bg-muted text-muted-foreground">
              <Icon aria-hidden className="size-4.5" />
            </span>
            <span className="min-w-40 flex-1">
              <span className="block font-medium text-foreground/75">
                {account.name}
              </span>
              <span className="block text-[13px] text-muted-foreground">
                {account.archivedAt === undefined
                  ? accountKind(account, t)
                  : t('accounts.archived.on', {
                      type: t(`accounts.types.${account.type}`),
                      date: formatDate(account.archivedAt, language),
                    })}
              </span>
            </span>
            <span className="font-semibold text-foreground/75 tabular-nums">
              <span className="sr-only">{t('accounts.columns.balance')}: </span>
              {formatMoney(account.balance, language)}
            </span>
            <Button
              variant="outline"
              size="lg"
              aria-label={t('accounts.restoreNamed', { name: account.name })}
              disabled={restoring === account.id}
              onClick={() => onRestore(account)}
            >
              {t('accounts.restore')}
            </Button>
          </li>
        )
      })}
    </ul>
  )
}

function NoAccounts({ onAdd }: { onAdd: (opener: HTMLElement) => void }) {
  const { t } = useTranslation()
  return (
    <div className="flex flex-col items-center gap-3 rounded-xl border border-dashed bg-card px-6 py-12 text-center">
      <span className="grid size-10 place-items-center rounded-[10px] bg-muted text-foreground/80">
        <Landmark aria-hidden className="size-5" />
      </span>
      <h2 className="font-semibold">{t('accounts.empty.title')}</h2>
      <p className="max-w-sm text-muted-foreground">
        {t('accounts.empty.description')}
      </p>
      <Button size="lg" onClick={(event) => onAdd(event.currentTarget)}>
        <Plus aria-hidden />
        {t('accounts.add')}
      </Button>
    </div>
  )
}
