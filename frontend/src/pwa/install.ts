import { useSyncExternalStore } from 'react'

/** Chromium's install event (Chrome and Edge on Android and desktop, Samsung Internet). */
export interface BeforeInstallPromptEvent extends Event {
  prompt(): Promise<void>
  readonly userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>
}

/**
 * How this browser installs MoneyTrail, if it can and the person has not just said no:
 * `prompt` is the browser's own dialog, `ios` the steps through the share menu, the only way
 * on an iPhone or iPad.
 */
export type InstallWay = 'prompt' | 'ios'

/** Long enough not to nag; a month later the offer comes back once. */
export const DISMISSED_FOR_DAYS = 30

const storageKey = 'moneytrail.installPrompt.dismissedAt'
const dayMs = 24 * 60 * 60 * 1000

interface State {
  event: BeforeInstallPromptEvent | null
  /** Dismissed during this visit: remembered even where storage is blocked. */
  dismissed: boolean
}

let state: State = { event: null, dismissed: false }
const listeners = new Set<() => void>()

function update(changes: Partial<State>) {
  state = { ...state, ...changes }
  for (const listener of listeners) {
    listener()
  }
}

/**
 * Starts listening for the browser's offer to install. Called before React renders: Chrome can
 * fire `beforeinstallprompt` straight after the page loads, and nobody would be listening yet.
 */
export function listenForInstallPrompt(target: Window = window) {
  target.addEventListener('beforeinstallprompt', (event) => {
    // Keeps Chrome's own install bar away: the app offers it in its own words (InstallPrompt).
    event.preventDefault()
    update({ event: event as BeforeInstallPromptEvent })
  })
  target.addEventListener('appinstalled', () => update({ event: null }))
}

/** An iPhone or iPad, whose browsers have no install event. iPadOS presents itself as a Mac. */
export function isIos(
  userAgent = navigator.userAgent,
  maxTouchPoints = navigator.maxTouchPoints,
) {
  return (
    /iPhone|iPad|iPod/.test(userAgent) ||
    (userAgent.includes('Macintosh') && maxTouchPoints > 1)
  )
}

/** Running as the installed app, opened from the home screen. */
export function isStandalone() {
  const standaloneDisplay =
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(display-mode: standalone)').matches
  // Safari on iOS only says so through this non-standard flag.
  const iosHomeScreen =
    (navigator as Navigator & { standalone?: boolean }).standalone === true
  return standaloneDisplay || iosHomeScreen
}

function dismissedRecently(now: number) {
  try {
    const dismissedAt = Number(localStorage.getItem(storageKey))
    return dismissedAt > 0 && now - dismissedAt < DISMISSED_FOR_DAYS * dayMs
  } catch {
    // Storage can be blocked by privacy settings; then only this visit remembers.
    return false
  }
}

function rememberDismissal(now: number) {
  try {
    localStorage.setItem(storageKey, String(now))
  } catch {
    // Storage blocked: the offer stays away for this visit only.
  }
}

/** The way to install MoneyTrail here, or `null` when there is nothing to offer. */
export function installWay(now = Date.now()): InstallWay | null {
  if (state.dismissed || dismissedRecently(now) || isStandalone()) {
    return null
  }
  if (state.event !== null) {
    return 'prompt'
  }
  return isIos() ? 'ios' : null
}

/** "Not now": the offer stays away for {@link DISMISSED_FOR_DAYS} days. */
export function dismissInstall(now = Date.now()) {
  rememberDismissal(now)
  update({ dismissed: true })
}

/**
 * Opens the browser's install dialog. An event can only show it once; saying no there counts as
 * "Not now", as Chrome would otherwise offer it again on the next page load.
 */
export async function install() {
  const event = state.event
  if (event === null) {
    return
  }
  update({ event: null })
  await event.prompt()
  const { outcome } = await event.userChoice
  if (outcome === 'dismissed') {
    dismissInstall()
  }
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/** What InstallPrompt shows; follows the browser's events and the person's answers. */
export function useInstallWay(): InstallWay | null {
  // The state object changes on every event, so it is the snapshot; the way is derived from it.
  useSyncExternalStore(subscribe, () => state)
  return installWay()
}

/** Back to a first visit in a browser without an install event. For tests. */
export function resetInstallPrompt() {
  state = { event: null, dismissed: false }
}
