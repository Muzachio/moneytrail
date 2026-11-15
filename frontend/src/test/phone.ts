import { vi } from 'vitest'

/**
 * Shows the app on a phone: `matchMedia` answers that the screen is narrower than Tailwind's `sm`.
 * Without it the tests get the wide layout, as jsdom has no `matchMedia`. Undone by
 * `vi.unstubAllGlobals()`.
 */
export function onPhone() {
  vi.stubGlobal('matchMedia', (media: string) => ({
    matches: false,
    media,
    addEventListener: () => {},
    removeEventListener: () => {},
  }))
}
