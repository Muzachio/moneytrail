import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'
import { i18n } from '@/i18n/i18n'

afterEach(async () => {
  cleanup()
  // Each test starts as a first visit from an English browser (jsdom's navigator.languages).
  localStorage.clear()
  await i18n.changeLanguage('en')
})
