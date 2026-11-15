import { useSyncExternalStore } from 'react'

/** Tailwind's `sm` breakpoint: below it, the app has the design canvas' phone layout. */
const WIDE = '(min-width: 40rem)'

function query() {
  // Browsers all have matchMedia; jsdom, where the tests run, does not, and gets the wide layout.
  return typeof window.matchMedia === 'function'
    ? window.matchMedia(WIDE)
    : undefined
}

function subscribe(onChange: () => void) {
  const media = query()
  media?.addEventListener('change', onChange)
  return () => media?.removeEventListener('change', onChange)
}

function isWide() {
  return query()?.matches ?? true
}

/**
 * Whether the screen is wider than a phone's, and stays in step when it turns or is resized.
 *
 * For what is *rendered* differently, not only laid out: the phone gets bottom tabs instead of the
 * header's navigation, and a shorter summary. Hiding one of two copies with CSS would still render
 * both, with every request and component twice, and two of each button in the tests, which have no
 * CSS.
 */
export function useWideScreen() {
  return useSyncExternalStore(subscribe, isWide, () => true)
}
