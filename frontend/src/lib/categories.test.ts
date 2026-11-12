import { Tag } from 'lucide-react'
import { describe, expect, it } from 'vitest'
import { i18n } from '@/i18n/i18n'
import { categoryIcon, categoryName } from './categories'

const t = i18n.getFixedT('en')

describe('categoryName', () => {
  it('translates the key of a default category the person has not renamed', () => {
    const food = {
      id: '1',
      kind: 'EXPENSE' as const,
      key: 'food',
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
      archived: false,
    }

    expect(categoryName(groceries, i18n.getFixedT('pt-PT'))).toBe('Groceries')
  })

  it('shows a key this version does not know as it is, with a plain icon', () => {
    const pets = {
      id: '1',
      kind: 'EXPENSE' as const,
      key: 'pets',
      archived: false,
    }

    expect(categoryName(pets, t)).toBe('pets')
    expect(categoryIcon(pets)).toBe(Tag)
  })
})
