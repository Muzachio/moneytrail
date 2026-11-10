import { describe, expect, it } from 'vitest'
import { resources } from './i18n'

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

const english = flatten(resources.en.translation)
const portuguese = flatten(resources['pt-PT'].translation)

describe('translations', () => {
  it('have the same keys in English and Portuguese', () => {
    expect(Object.keys(portuguese).sort()).toEqual(Object.keys(english).sort())
  })

  it('leave no message empty', () => {
    for (const message of [
      ...Object.values(english),
      ...Object.values(portuguese),
    ]) {
      expect(message.trim()).not.toBe('')
    }
  })

  it('use the same {{variables}} in both languages', () => {
    for (const [key, message] of Object.entries(english)) {
      expect(variables(portuguese[key]).sort(), key).toEqual(
        variables(message).sort(),
      )
    }
  })
})
