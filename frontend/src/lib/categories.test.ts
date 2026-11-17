import { PawPrint, Tag, Utensils } from 'lucide-react'
import { describe, expect, it } from 'vitest'
import type { CategoryIcon } from '@/api/categories'
import { i18n } from '@/i18n/i18n'
import { categoryIcon, categoryIconNames, categoryName } from './categories'

const t = i18n.getFixedT('en')

describe('categoryName', () => {
  it('translates the key of a default category the person has not renamed', () => {
    const food = {
      id: '1',
      kind: 'EXPENSE' as const,
      key: 'food',
      icon: 'utensils' as const,
      archived: false,
    }

    expect(categoryName(food, t)).toBe('Food')
    expect(categoryName(food, i18n.getFixedT('pt-PT'))).toBe('Alimentação')
  })

  it("shows the person's name once they renamed it, in every language", () => {
    const groceries = {
      id: '1',
      kind: 'EXPENSE' as const,
      key: 'food',
      name: 'Groceries',
      icon: 'utensils' as const,
      archived: false,
    }

    expect(categoryName(groceries, i18n.getFixedT('pt-PT'))).toBe('Groceries')
  })

  it('shows a key this version does not know as it is', () => {
    const pets = {
      id: '1',
      kind: 'EXPENSE' as const,
      key: 'pets',
      icon: 'tag' as const,
      archived: false,
    }

    expect(categoryName(pets, t)).toBe('pets')
  })
})

describe('categoryIcon', () => {
  it('draws the icon the API names, for a default category and for one of the person’s own', () => {
    expect(categoryIcon({ icon: 'utensils' })).toBe(Utensils)
    expect(categoryIcon({ icon: 'paw-print' })).toBe(PawPrint)
  })

  it('draws an icon this version does not know as the plain tag', () => {
    expect(categoryIcon({ icon: 'dragon' as CategoryIcon })).toBe(Tag)
    expect(categoryIcon({ icon: 'toString' as CategoryIcon })).toBe(Tag)
  })

  it('offers the default categories’ icons first, then the rest', () => {
    expect(categoryIconNames).toHaveLength(24)
    expect(categoryIconNames.slice(0, 3)).toEqual([
      'house',
      'utensils',
      'tram-front',
    ])
    expect(new Set(categoryIconNames).size).toBe(24)
  })
})
