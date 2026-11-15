import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { RegisterSWOptions } from 'vite-plugin-pwa/types'
import { UpdatePrompt } from './UpdatePrompt'

// vite-plugin-pwa's hook, driven by the test: whether a new version is waiting, and the options
// UpdatePrompt registered with.
const sw = vi.hoisted(() => ({
  needRefresh: false,
  options: undefined as RegisterSWOptions | undefined,
  updateServiceWorker: vi.fn(() => Promise.resolve()),
}))

vi.mock('virtual:pwa-register/react', () => ({
  useRegisterSW: (options: RegisterSWOptions) => {
    sw.options = options
    return {
      needRefresh: [sw.needRefresh, vi.fn()],
      offlineReady: [false, vi.fn()],
      updateServiceWorker: sw.updateServiceWorker,
    }
  },
}))

function setVisibility(state: DocumentVisibilityState) {
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue(state)
  document.dispatchEvent(new Event('visibilitychange'))
}

describe('UpdatePrompt', () => {
  beforeEach(() => {
    sw.needRefresh = false
    sw.updateServiceWorker.mockClear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('shows nothing while the running version is the latest', () => {
    render(<UpdatePrompt />)

    expect(screen.getByRole('status')).toBeEmptyDOMElement()
  })

  it('offers the new version after a deploy and switches to it on Update', () => {
    sw.needRefresh = true
    render(<UpdatePrompt />)

    expect(screen.getByRole('status')).toHaveTextContent(
      'A new version of MoneyTrail is ready.',
    )
    fireEvent.click(screen.getByRole('button', { name: 'Update' }))

    expect(sw.updateServiceWorker).toHaveBeenCalledOnce()
  })

  it('looks for a new version each time the app comes back to the screen', () => {
    render(<UpdatePrompt />)
    const registration = {
      update: vi.fn(() => Promise.resolve()),
    } as unknown as ServiceWorkerRegistration
    sw.options?.onRegisteredSW?.('/sw.js', registration)

    setVisibility('hidden')
    expect(registration.update).not.toHaveBeenCalled()

    setVisibility('visible')
    expect(registration.update).toHaveBeenCalledOnce()
  })

  // An unhandled rejection would fail the test run.
  it('lets a failed check go, e.g. offline', async () => {
    render(<UpdatePrompt />)
    const registration = {
      update: vi.fn(() => Promise.reject(new Error('offline'))),
    } as unknown as ServiceWorkerRegistration
    sw.options?.onRegisteredSW?.('/sw.js', registration)

    setVisibility('visible')
    await new Promise((resolve) => setTimeout(resolve))

    expect(registration.update).toHaveBeenCalledOnce()
  })
})
