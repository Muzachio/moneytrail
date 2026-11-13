import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'
import { dataOrThrow } from './problem'
import type { components } from './schema'

export type Category = components['schemas']['Category']
export type CategoryKind = components['schemas']['CategoryKind']
export type CategoryUpdate = components['schemas']['CategoryUpdate']

export const categoriesKey = ['categories'] as const

/** Every category of the signed-in user, archived ones included: expenses first, then income. */
export function useCategories() {
  return useQuery({
    queryKey: categoriesKey,
    queryFn: async () => dataOrThrow(await api.GET('/v1/categories')).items,
  })
}

/**
 * Adds a category of the user's own. It moves no money, so it takes no Idempotency-Key: a retry
 * of a request that went through answers `category_name_taken`.
 */
export function useCreateCategory() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (category: { kind: CategoryKind; name: string }) =>
      dataOrThrow(await api.POST('/v1/categories', { body: category })),
    // Fetched again rather than inserted here: the API decides the order of the list.
    onSuccess: () => queryClient.invalidateQueries({ queryKey: categoriesKey }),
  })
}

/** Renames, archives or restores a category. */
export function useUpdateCategory() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ id, ...change }: CategoryUpdate & { id: string }) =>
      dataOrThrow(
        await api.PATCH('/v1/categories/{id}', {
          params: { path: { id } },
          body: change,
        }),
      ),
    onSuccess: (updated) =>
      queryClient.setQueryData<Category[]>(categoriesKey, (categories) =>
        categories?.map((category) =>
          category.id === updated.id ? updated : category,
        ),
      ),
  })
}
