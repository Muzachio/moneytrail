import { type FormEvent, useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { type Account, useAccounts } from '@/api/accounts'
import { type Category, useCategories } from '@/api/categories'
import {
  type GroupMirror,
  useGroupMirror,
  useTurnOffGroupMirror,
  useTurnOnGroupMirror,
} from '@/api/groupMirror'
import type { Group } from '@/api/groups'
import { LoadFailed, Loading } from '@/components/QueryState'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Field, FieldLabel } from '@/components/ui/field'
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select'
import { categoryName } from '@/lib/categories'
import { returnFocus } from '@/lib/focus'
import { apiErrorMessage } from '@/lib/forms'

/**
 * Whether the person's share of the group is recorded in their own ledger too, and with which
 * category and account (docs/adr/0015-group-shares-in-the-personal-ledger.md). Each member decides
 * for themselves; nobody else sees it.
 */
export function GroupMirrorDialog({
  group,
  open,
  onOpenChange,
  opener,
}: {
  group: Group
  open: boolean
  onOpenChange: (open: boolean) => void
  opener: HTMLElement | null
}) {
  const { t } = useTranslation()
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        className="gap-5 p-6 sm:max-w-115"
        onCloseAutoFocus={(event) => returnFocus(event, opener)}
      >
        <DialogHeader>
          <DialogTitle>{t('groupMirror.title')}</DialogTitle>
          <DialogDescription>
            {t('groupMirror.description', { name: group.name })}
          </DialogDescription>
        </DialogHeader>
        {open && (
          <MirrorForm group={group} onDone={() => onOpenChange(false)} />
        )}
      </DialogContent>
    </Dialog>
  )
}

function MirrorForm({ group, onDone }: { group: Group; onDone: () => void }) {
  const { t } = useTranslation()
  const mirror = useGroupMirror(group.id)
  const accounts = useAccounts()
  const categories = useCategories()

  if (mirror.isPending || accounts.isPending || categories.isPending) {
    return <Loading>{t('groupMirror.loading')}</Loading>
  }
  if (mirror.isError || accounts.isError || categories.isError) {
    return (
      <LoadFailed
        onRetry={() => {
          void mirror.refetch()
          void accounts.refetch()
          void categories.refetch()
        }}
      >
        {t('groupMirror.loadFailed')}
      </LoadFailed>
    )
  }
  const usable = accounts.data.filter(
    (account) => !account.archived && account.type !== 'GROUP',
  )
  const expenseCategories = categories.data.filter(
    (category) => category.kind === 'EXPENSE' && !category.archived,
  )
  if (usable.length === 0) {
    return (
      <p className="text-muted-foreground">
        {t('groupMirror.noAccount')}{' '}
        <Link to="/accounts" className="font-medium text-foreground underline">
          {t('groupMirror.toAccounts')}
        </Link>
      </p>
    )
  }
  return (
    <Settings
      group={group}
      mirror={mirror.data}
      accounts={usable}
      categories={expenseCategories}
      onDone={onDone}
    />
  )
}

function Settings({
  group,
  mirror,
  accounts,
  categories,
  onDone,
}: {
  group: Group
  mirror: GroupMirror
  accounts: Account[]
  categories: Category[]
  onDone: () => void
}) {
  const { t } = useTranslation()
  const categoryId = useId()
  const accountId = useId()
  const turnOn = useTurnOnGroupMirror(group.id)
  const turnOff = useTurnOffGroupMirror(group.id)
  const fallback =
    categories.find((category) => category.key === 'other') ?? categories[0]
  const [category, setCategory] = useState(
    mirror.categoryId ?? fallback?.id ?? '',
  )
  const [account, setAccount] = useState(
    mirror.accountId ?? accounts[0]?.id ?? '',
  )
  const failed = turnOn.error ?? turnOff.error
  const busy = turnOn.isPending || turnOff.isPending

  const save = (event: FormEvent) => {
    event.preventDefault()
    turnOff.reset()
    turnOn.mutate(
      { categoryId: category, accountId: account },
      { onSuccess: onDone },
    )
  }

  return (
    <form onSubmit={save} className="flex flex-col gap-4">
      <p className="rounded-lg bg-muted px-3 py-2.5 text-sm">
        {mirror.enabled ? t('groupMirror.on') : t('groupMirror.howItStarts')}
      </p>
      <Field>
        <FieldLabel htmlFor={accountId}>{t('groupMirror.account')}</FieldLabel>
        <NativeSelect
          id={accountId}
          value={account}
          onChange={(event) => setAccount(event.target.value)}
        >
          {accounts.map((option) => (
            <NativeSelectOption key={option.id} value={option.id}>
              {option.name}
            </NativeSelectOption>
          ))}
        </NativeSelect>
        <p className="text-sm text-muted-foreground">
          {t('groupMirror.accountHint')}
        </p>
      </Field>
      <Field>
        <FieldLabel htmlFor={categoryId}>
          {t('groupMirror.category')}
        </FieldLabel>
        <NativeSelect
          id={categoryId}
          value={category}
          onChange={(event) => setCategory(event.target.value)}
        >
          {categories.map((option) => (
            <NativeSelectOption key={option.id} value={option.id}>
              {categoryName(option, t)}
            </NativeSelectOption>
          ))}
        </NativeSelect>
        <p className="text-sm text-muted-foreground">
          {t('groupMirror.categoryHint')}
        </p>
      </Field>
      {failed && (
        <p
          role="alert"
          className="rounded-lg bg-destructive/10 px-3 py-2.5 text-destructive"
        >
          {apiErrorMessage(failed, t)}
        </p>
      )}
      <DialogFooter className="gap-2 sm:justify-between">
        {mirror.enabled ? (
          <Button
            type="button"
            variant="outline"
            disabled={busy}
            onClick={() => {
              turnOn.reset()
              turnOff.mutate(undefined, { onSuccess: onDone })
            }}
          >
            {t('groupMirror.turnOff')}
          </Button>
        ) : (
          <span />
        )}
        <Button type="submit" disabled={busy}>
          {mirror.enabled ? t('groupMirror.save') : t('groupMirror.turnOn')}
        </Button>
      </DialogFooter>
    </form>
  )
}
