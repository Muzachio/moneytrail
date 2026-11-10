/** The languages MoneyTrail speaks, as BCP 47 tags: also the value of `<html lang>`. */
export const languages = ['en', 'pt-PT'] as const

export type Language = (typeof languages)[number]

/**
 * Each language named in itself, never translated: someone who cannot read the current language
 * still recognises their own.
 */
export const languageNames: Record<Language, string> = {
  en: 'English',
  'pt-PT': 'Português',
}

const storageKey = 'moneytrail.language'

export function isLanguage(value: unknown): value is Language {
  return languages.some((language) => language === value)
}

/**
 * The first of the browser's preferred languages that MoneyTrail speaks, English otherwise. Any
 * Portuguese gets European Portuguese, the only variant there is.
 */
export function browserLanguage(preferred: readonly string[]): Language {
  for (const tag of preferred) {
    const primary = tag.toLowerCase().split('-')[0]
    if (primary === 'pt') {
      return 'pt-PT'
    }
    if (primary === 'en') {
      return 'en'
    }
  }
  return 'en'
}

/** The language picked with the toggle on an earlier visit, if any. */
export function storedLanguage(): Language | undefined {
  try {
    const stored = localStorage.getItem(storageKey)
    return isLanguage(stored) ? stored : undefined
  } catch {
    // Storage can be blocked by privacy settings; the browser's language still applies.
    return undefined
  }
}

export function storeLanguage(language: Language) {
  try {
    localStorage.setItem(storageKey, language)
  } catch {
    // The switch still works for this visit, it is just not remembered.
  }
}

/**
 * The person's own choice wins; until they make one, the browser decides. Only a choice is
 * stored, so someone who never picks keeps following their browser.
 */
export function initialLanguage(): Language {
  return storedLanguage() ?? browserLanguage(navigator.languages)
}
