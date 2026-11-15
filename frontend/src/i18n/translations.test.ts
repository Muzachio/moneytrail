import { describe, expect, it } from 'vitest'
import { i18n, resources } from './i18n'
import { type Language, languages } from './language'

type Messages = { [key: string]: string | Messages }

/** `{ nav: { summary: 'Summary' } }` → `{ 'nav.summary': 'Summary' }` */
function flatten(messages: Messages, prefix = ''): Record<string, string> {
  return Object.fromEntries(
    Object.entries(messages).flatMap(([key, value]) =>
      typeof value === 'string'
        ? [[prefix + key, value]]
        : Object.entries(flatten(value, `${prefix}${key}.`)),
    ),
  )
}

function variables(message: string) {
  return [...message.matchAll(/{{\s*(\w+)\s*}}/g)].map((match) => match[1])
}

const pluralSuffix = /_(zero|one|two|few|many|other)$/

/** The plural forms a language needs, as i18next looks them up: Intl's categories. */
function pluralForms(language: Language) {
  return [
    ...new Intl.PluralRules(language).resolvedOptions().pluralCategories,
  ].sort()
}

const messages = Object.fromEntries(
  languages.map((language) => [
    language,
    flatten(resources[language].translation),
  ]),
) as Record<Language, Record<string, string>>

/** Plural keys under their base name: `transactions.showing_one` → `transactions.showing`. */
function keysOf(language: Language) {
  return [
    ...new Set(
      Object.keys(messages[language]).map((key) =>
        key.replace(pluralSuffix, ''),
      ),
    ),
  ].sort()
}

/** `{ 'transactions.showing': ['one', 'other'] }` for every key with plural forms. */
function pluralsOf(language: Language) {
  const plurals: Record<string, string[]> = {}
  for (const key of Object.keys(messages[language])) {
    const match = pluralSuffix.exec(key)
    if (match) {
      const base = key.slice(0, match.index)
      plurals[base] = [...(plurals[base] ?? []), match[1]].sort()
    }
  }
  return plurals
}

describe('translations', () => {
  it('have the same keys in English and Portuguese', () => {
    expect(keysOf('pt-PT')).toEqual(keysOf('en'))
  })

  it('have every plural form each language needs', () => {
    // Portuguese has a third form, "many", for whole millions: without it i18next would show
    // the key itself for 1 000 000 transactions.
    for (const language of languages) {
      for (const [key, forms] of Object.entries(pluralsOf(language))) {
        expect(forms, `${language} ${key}`).toEqual(pluralForms(language))
      }
    }
    expect(Object.keys(pluralsOf('pt-PT')).sort()).toEqual(
      Object.keys(pluralsOf('en')).sort(),
    )
  })

  it('count a million in Portuguese with a message, not its key', async () => {
    await i18n.changeLanguage('pt-PT')

    expect(
      i18n.t('transactions.summary.count', {
        count: 1_000_000,
        month: 'outubro',
      }),
    ).toBe('1000000 movimentos em outubro')
  })

  it('leave no message empty', () => {
    for (const language of languages) {
      for (const message of Object.values(messages[language])) {
        expect(message.trim()).not.toBe('')
      }
    }
  })

  it('use the same {{variables}} in both languages', () => {
    const english = messages.en
    const portuguese = messages['pt-PT']
    for (const [key, message] of Object.entries(portuguese)) {
      const base = key.replace(pluralSuffix, '')
      const source = english[key] ?? english[`${base}_other`]
      expect(variables(message).sort(), key).toEqual(variables(source).sort())
    }
  })
})
