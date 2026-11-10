import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import {
  initialLanguage,
  type Language,
  languages,
  storeLanguage,
} from './language'
import en from './locales/en.json'
import ptPT from './locales/pt-PT.json'

// English is the source language: `satisfies` fails the type check when Portuguese lacks a key.
export const resources = {
  en: { translation: en },
  'pt-PT': { translation: ptPT satisfies typeof en },
}

// Screen readers choose their voice from <html lang>, and browsers their hyphenation and the
// language they offer to translate from. Registered before init(), which fires the first change.
i18n.on('languageChanged', (language) => {
  document.documentElement.lang = language
})

// With the resources inline there is nothing to load, so i18next is ready when init() returns.
void i18n.use(initReactI18next).init({
  resources,
  lng: initialLanguage(),
  supportedLngs: languages,
  fallbackLng: 'en',
  // React escapes what it renders already.
  interpolation: { escapeValue: false },
})

/** Switches the language now and remembers it for the next visits. */
export function changeLanguage(language: Language) {
  storeLanguage(language)
  return i18n.changeLanguage(language)
}

export { i18n }
