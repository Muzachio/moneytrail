import { PawPrint, Tag, Utensils } from 'lucide-react'
import { describe, expect, it } from 'vitest'
import type { CategoryIcon, CategoryKey } from '@/api/categories'
import { i18n } from '@/i18n/i18n'
import {
  categoryIcon,
  categoryIconNames,
  categoryKeys,
  categoryName,
  knownNames,
  naming,
} from './categories'

const english = i18n.getFixedT('en')
const portuguese = i18n.getFixedT('pt-PT')

describe('categoryName', () => {
  it('translates a known name into the current language', () => {
    expect(categoryName({ key: 'food' }, english)).toBe('Food')
    expect(categoryName({ key: 'food' }, portuguese)).toBe('Alimentação')
    expect(categoryName({ key: 'gym' }, english)).toBe('Gym')
    expect(categoryName({ key: 'gym' }, portuguese)).toBe('Ginásio')
  })

  it('shows a typed name as it is, in every language', () => {
    expect(categoryName({ name: 'Pilates' }, portuguese)).toBe('Pilates')
    expect(categoryName({ name: 'Pilates' }, english)).toBe('Pilates')
  })

  it('shows a key this version does not know as it is', () => {
    expect(categoryName({ key: 'pilates' as CategoryKey }, english)).toBe(
      'pilates',
    )
  })
})

describe('naming', () => {
  it('finds the known name typed in the current language, whatever its case, accents and spaces', () => {
    expect(naming('EXPENSE', 'Ginásio', portuguese)).toEqual({ key: 'gym' })
    expect(naming('EXPENSE', '  ginasio ', portuguese)).toEqual({ key: 'gym' })
    expect(naming('EXPENSE', 'GINÁSIO', portuguese)).toEqual({ key: 'gym' })
    expect(naming('EXPENSE', 'gym', english)).toEqual({ key: 'gym' })
    expect(naming('INCOME', 'subsidio de natal', portuguese)).toEqual({
      key: 'christmas-allowance',
    })
  })

  it("finds the defaults' names too", () => {
    expect(naming('EXPENSE', 'Alimentação', portuguese)).toEqual({
      key: 'food',
    })
    expect(naming('INCOME', 'Other income', english)).toEqual({
      key: 'other-income',
    })
  })

  it('leaves a name typed in the other language as it is', () => {
    expect(naming('EXPENSE', 'Pets', portuguese)).toEqual({ name: 'Pets' })
    expect(naming('EXPENSE', 'Ginásio', english)).toEqual({ name: 'Ginásio' })
  })

  it('only looks among the names of the kind', () => {
    expect(naming('EXPENSE', 'Presentes', portuguese)).toEqual({
      key: 'gifts-given',
    })
    expect(naming('INCOME', 'Presentes', portuguese)).toEqual({
      key: 'gifts',
    })
    expect(naming('INCOME', 'Ginásio', portuguese)).toEqual({
      name: 'Ginásio',
    })
  })

  it('keeps any other name as typed, without the spaces around it', () => {
    expect(naming('EXPENSE', '  Pilates  ', portuguese)).toEqual({
      name: 'Pilates',
    })
  })

  it('finds every known name in both languages by its own key', () => {
    for (const [language, t] of [
      ['en', english],
      ['pt-PT', portuguese],
    ] as const) {
      for (const key of categoryKeys) {
        // The first match is the key itself: no two names of a kind read the same.
        expect(
          naming(knownNames[key], t(`categories.names.${key}`), t),
          `${key} in ${language}`,
        ).toEqual({ key })
      }
    }
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
