import '@testing-library/jest-dom/vitest'
import { cleanup, configure } from '@testing-library/react'
import { afterEach } from 'vitest'
import { i18n } from '@/i18n/i18n'

// A whole page waits for several requests before its first render with data, which can take more
// than the default second on a busy CI runner.
configure({ asyncUtilTimeout: 3000 })

afterEach(async () => {
  cleanup()
  // Each test starts as a first visit from an English browser (jsdom's navigator.languages).
  localStorage.clear()
  await i18n.changeLanguage('en')
})
