import 'i18next'
import type en from './locales/en.json'

// Types t() from the English file: a key that does not exist, or a missing {{variable}}, fails
// the type check instead of showing the raw key on screen.
declare module 'i18next' {
  interface CustomTypeOptions {
    resources: { translation: typeof en }
  }
}
