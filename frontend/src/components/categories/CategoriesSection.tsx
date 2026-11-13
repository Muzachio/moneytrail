import { Plus } from 'lucide-react'
import { useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  type Category,
  type CategoryKind,
  useCategories,
  useCreateCategory,
  useUpdateCategory,
} from '@/api/categories'
import { ApiError } from '@/api/problem'
import { ArchivedSection } from '@/components/ArchivedSection'
import { NameDialog } from '@/components/NameDialog'
import { LoadFailed, Loading } from '@/components/QueryState'
import { RowMenu } from '@/components/RowMenu'
import { Button } from '@/components/ui/button'
import { categoryIcon, categoryName } from '@/lib/categories'
import { apiErrorMessage } from '@/lib/forms'

const kinds = ['EXPENSE', 'INCOME'] as const satisfies readonly CategoryKind[]

/** What the name dialog is for, and the button that opened it, to give the focus back to. */
type Editing = { opener: HTMLElement | null } & (
  | { action: 'add'; kind: CategoryKind }
  | { action: 'rename'; category: Category }
)

/**
 * Adding, renaming, archiving and restoring categories. The design canvas has no screen for them,
 * so they sit below the accounts, in the same style.
 */
export function CategoriesSection() {
  const { t } = useTranslation()
  const titleId = useId()
  const categories = useCategories()
  const create = useCreateCategory()
  const rename = useUpdateCategory()
  const archive = useUpdateCategory()
  const [editing, setEditing] = useState<Editing | null>(null)
  // Kept while the dialog closes, so its title does not change under the fade-out.
  const [lastEditing, setLastEditing] = useState<Editing | null>(null)

  const edit = (next: Editing) => {
    setEditing(next)
    setLastEditing(next)
  }

  /** Two active categories of one kind cannot share a name: the API says so at the field. */
  const nameTaken = (kind: CategoryKind) => (error: unknown) =>
    error instanceof ApiError && error.code === 'category_name_taken'
      ? t(`categories.nameTaken.${kind}`)
      : undefined

  let content
  if (categories.isPending) {
    content = <Loading>{t('categories.loading')}</Loading>
  } else if (categories.isError) {
    content = (
      <LoadFailed onRetry={() => void categories.refetch()}>
        {t('categories.loadFailed')}
      </LoadFailed>
    )
  } else {
    const archived = categories.data.filter((category) => category.archived)
    const failed = archive.isError
      ? categories.data.find(({ id }) => id === archive.variables.id)
      : undefined
    content = (
      <>
        {failed && archive.isError && (
          <p
            role="alert"
            className="rounded-lg bg-destructive/10 px-3 py-2.5 text-destructive"
          >
            {archive.variables.archived
              ? t('categories.archiveFailed', {
                  name: categoryName(failed, t),
                })
              : t('categories.restoreFailed', {
                  name: categoryName(failed, t),
                })}{' '}
            {nameTaken(failed.kind)(archive.error) ??
              apiErrorMessage(archive.error, t)}
          </p>
        )}
        <div className="grid items-start gap-4 md:grid-cols-2">
          {kinds.map((kind) => (
            <CategoryList
              key={kind}
              kind={kind}
              categories={categories.data.filter(
                (category) => category.kind === kind && !category.archived,
              )}
              onAdd={(opener) => edit({ action: 'add', kind, opener })}
              onRename={(category, opener) =>
                edit({ action: 'rename', category, opener })
              }
              onArchive={(category) =>
                archive.mutate({ id: category.id, archived: true })
              }
            />
          ))}
        </div>
        {archived.length > 0 && (
          <ArchivedSection
            headingLevel={3}
            title={t('categories.archived.title', { count: archived.length })}
          >
            <ul className="divide-y divide-border/60 rounded-xl border bg-card">
              {archived.map((category) => {
                const Icon = categoryIcon(category)
                const name = categoryName(category, t)
                return (
                  <li
                    key={category.id}
                    className="flex items-center gap-3 px-4 py-2.5"
                  >
                    <span className="grid size-8 flex-none place-items-center rounded-lg bg-muted text-muted-foreground">
                      <Icon aria-hidden className="size-4" />
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate font-medium text-foreground/75">
                        {name}
                      </span>
                      <span className="block text-[13px] text-muted-foreground">
                        {t(`categories.kind.${category.kind}`)}
                      </span>
                    </span>
                    <Button
                      variant="outline"
                      size="lg"
                      aria-label={t('categories.restoreNamed', { name })}
                      disabled={
                        archive.isPending &&
                        archive.variables.id === category.id
                      }
                      onClick={() =>
                        archive.mutate({ id: category.id, archived: false })
                      }
                    >
                      {t('categories.restore')}
                    </Button>
                  </li>
                )
              })}
            </ul>
          </ArchivedSection>
        )}
      </>
    )
  }

  const dialog = editing ?? lastEditing
  return (
    <section aria-labelledby={titleId} className="flex flex-col gap-4">
      <div>
        <h2
          id={titleId}
          className="font-heading text-xl font-semibold tracking-tight"
        >
          {t('categories.title')}
        </h2>
        <p className="mt-1 text-muted-foreground">{t('categories.lead')}</p>
      </div>
      {content}
      <NameDialog
        open={editing !== null}
        onOpenChange={(open) => !open && setEditing(null)}
        title={
          dialog?.action === 'rename'
            ? t('categories.renameTitle')
            : t(`categories.new.${dialog?.kind ?? 'EXPENSE'}`)
        }
        submitLabel={
          dialog?.action === 'rename' ? t('common.save') : t('categories.add')
        }
        initialName={
          dialog?.action === 'rename'
            ? categoryName(dialog.category, t)
            : undefined
        }
        onSave={(name) =>
          dialog?.action === 'rename'
            ? rename.mutateAsync({ id: dialog.category.id, name })
            : create.mutateAsync({ kind: dialog?.kind ?? 'EXPENSE', name })
        }
        nameError={nameTaken(
          dialog?.action === 'rename'
            ? dialog.category.kind
            : (dialog?.kind ?? 'EXPENSE'),
        )}
        returnFocusTo={dialog?.opener}
      />
    </section>
  )
}

function CategoryList({
  kind,
  categories,
  onAdd,
  onRename,
  onArchive,
}: {
  kind: CategoryKind
  categories: Category[]
  onAdd: (opener: HTMLElement) => void
  onRename: (category: Category, menuButton: HTMLElement | null) => void
  onArchive: (category: Category) => void
}) {
  const { t } = useTranslation()
  const titleId = useId()

  return (
    <section aria-labelledby={titleId} className="rounded-xl border bg-card">
      <h3
        id={titleId}
        className="border-b px-4 py-2.5 text-[15px] font-semibold"
      >
        {t(`categories.kinds.${kind}`)}
      </h3>
      {categories.length === 0 ? (
        <p className="px-4 py-3 text-muted-foreground">
          {t(`categories.none.${kind}`)}
        </p>
      ) : (
        <ul className="divide-y divide-border/60">
          {categories.map((category) => {
            const Icon = categoryIcon(category)
            const name = categoryName(category, t)
            return (
              <li
                key={category.id}
                className="flex items-center gap-3 py-1.5 pr-2 pl-4"
              >
                <span className="grid size-8 flex-none place-items-center rounded-lg bg-muted text-foreground/80">
                  <Icon aria-hidden className="size-4" />
                </span>
                <span className="min-w-0 flex-1 font-medium wrap-break-word">
                  {name}
                </span>
                <RowMenu
                  label={t('rowMenu.label', { name })}
                  archiveHint={t('categories.archiveHint')}
                  onRename={(menuButton) => onRename(category, menuButton)}
                  onArchive={() => onArchive(category)}
                />
              </li>
            )
          })}
        </ul>
      )}
      <div className="border-t p-2">
        <Button
          variant="ghost"
          size="lg"
          className="w-full justify-start text-foreground/75"
          onClick={(event) => onAdd(event.currentTarget)}
        >
          <Plus aria-hidden />
          {t(`categories.new.${kind}`)}
        </Button>
      </div>
    </section>
  )
}
