import { act, fireEvent, render, screen } from '@testing-library/react'
import {
  afterEach,
  beforeAll,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from 'vitest'
import { i18n } from '@/i18n/i18n'
import { InstallPrompt } from './InstallPrompt'
import {
  type BeforeInstallPromptEvent,
  DISMISSED_FOR_DAYS,
  isIos,
  listenForInstallPrompt,
  resetInstallPrompt,
} from './install'

const IPHONE =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Mobile/15E148 Safari/604.1'
const IPAD_AS_MAC =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Safari/605.1.15'
const ANDROID_CHROME =
  'Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36'

const dayMs = 24 * 60 * 60 * 1000

/** What Chrome fires when MoneyTrail can be installed. */
function offerInstall(outcome: 'accepted' | 'dismissed' = 'accepted') {
  const event = Object.assign(
    new Event('beforeinstallprompt', { cancelable: true }),
    {
      prompt: vi.fn(() => Promise.resolve()),
      userChoice: Promise.resolve({ outcome }),
    },
  ) satisfies BeforeInstallPromptEvent
  act(() => {
    window.dispatchEvent(event)
  })
  return event
}

/** The next visit: the page loads again, with what was stored. */
function reload() {
  cleanup()
  resetInstallPrompt()
  return render(<InstallPrompt />)
}

function cleanup() {
  document.body.innerHTML = ''
}

function standalone() {
  vi.stubGlobal('matchMedia', (media: string) => ({
    matches: media === '(display-mode: standalone)',
    media,
    addEventListener: () => {},
    removeEventListener: () => {},
  }))
}

describe('InstallPrompt', () => {
  beforeAll(() => {
    listenForInstallPrompt()
  })

  beforeEach(() => {
    resetInstallPrompt()
    vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue(ANDROID_CHROME)
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  describe('where the browser offers to install (Chrome, Edge)', () => {
    it('offers it in its own words and keeps the browser bar away', () => {
      render(<InstallPrompt />)
      expect(screen.queryByRole('region')).not.toBeInTheDocument()

      const event = offerInstall()

      expect(event.defaultPrevented).toBe(true)
      expect(
        screen.getByRole('region', { name: 'Install MoneyTrail' }),
      ).toHaveTextContent('in a window of its own')
    })

    it('opens the browser dialog from Install, then goes away', async () => {
      render(<InstallPrompt />)
      const event = offerInstall('accepted')

      fireEvent.click(screen.getByRole('button', { name: 'Install' }))

      expect(event.prompt).toHaveBeenCalledOnce()
      expect(screen.queryByRole('region')).not.toBeInTheDocument()
      await act(() => event.userChoice)
      act(() => {
        window.dispatchEvent(new Event('appinstalled'))
      })
      expect(screen.queryByRole('region')).not.toBeInTheDocument()
    })

    it('stays away after the browser dialog is cancelled', async () => {
      render(<InstallPrompt />)
      const event = offerInstall('dismissed')

      fireEvent.click(screen.getByRole('button', { name: 'Install' }))
      await act(() => event.userChoice)

      reload()
      offerInstall()
      expect(screen.queryByRole('region')).not.toBeInTheDocument()
    })

    it(`does not come back for ${DISMISSED_FOR_DAYS} days after "Not now"`, () => {
      const now = new Date('2026-11-15T12:00:00Z')
      vi.useFakeTimers({ now, toFake: ['Date'] })
      render(<InstallPrompt />)
      offerInstall()

      fireEvent.click(screen.getByRole('button', { name: 'Not now' }))

      expect(screen.queryByRole('region')).not.toBeInTheDocument()
      vi.setSystemTime(now.getTime() + (DISMISSED_FOR_DAYS - 1) * dayMs)
      reload()
      offerInstall()
      expect(screen.queryByRole('region')).not.toBeInTheDocument()

      vi.setSystemTime(now.getTime() + DISMISSED_FOR_DAYS * dayMs)
      reload()
      offerInstall()
      expect(
        screen.getByRole('region', { name: 'Install MoneyTrail' }),
      ).toBeInTheDocument()
    })
  })

  describe('on an iPhone or iPad', () => {
    beforeEach(() => {
      vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue(IPHONE)
    })

    it('shows the steps to add it to the home screen', () => {
      render(<InstallPrompt />)

      const prompt = screen.getByRole('region', { name: 'Install MoneyTrail' })
      expect(
        [...prompt.querySelectorAll('li')].map((step) => step.textContent),
      ).toEqual([
        'Tap Share. In Safari on iOS 26, it is in the ⋯ menu.',
        'Choose Add to Home Screen. You may need to scroll down to find it.',
        'Tap Add.',
      ])
      expect(
        screen.queryByRole('button', { name: 'Install' }),
      ).not.toBeInTheDocument()
    })

    it('names the steps as Safari does in Portuguese', async () => {
      await i18n.changeLanguage('pt-PT')
      render(<InstallPrompt />)

      expect(
        screen.getByRole('region', { name: 'Instala o MoneyTrail' }),
      ).toHaveTextContent('Escolhe «Adicionar ao ecrã principal».')
    })

    it('can be dismissed', () => {
      render(<InstallPrompt />)

      fireEvent.click(screen.getByRole('button', { name: 'Not now' }))

      expect(screen.queryByRole('region')).not.toBeInTheDocument()
      reload()
      expect(screen.queryByRole('region')).not.toBeInTheDocument()
    })

    it('shows nothing in the app opened from the home screen', () => {
      // Safari's own flag; jsdom has no such property.
      Object.defineProperty(navigator, 'standalone', {
        value: true,
        configurable: true,
      })
      try {
        render(<InstallPrompt />)

        expect(screen.queryByRole('region')).not.toBeInTheDocument()
      } finally {
        Reflect.deleteProperty(navigator, 'standalone')
      }
    })
  })

  it('shows nothing where the browser cannot install it', () => {
    render(<InstallPrompt />)

    expect(screen.queryByRole('region')).not.toBeInTheDocument()
  })

  it('shows nothing once installed', () => {
    standalone()
    render(<InstallPrompt />)

    offerInstall()

    expect(screen.queryByRole('region')).not.toBeInTheDocument()
  })
})

describe('isIos', () => {
  it('knows an iPhone and an iPad, which says it is a Mac with a touch screen', () => {
    expect(isIos(IPHONE, 5)).toBe(true)
    expect(isIos(IPAD_AS_MAC, 5)).toBe(true)
  })

  it('leaves out Macs and Android', () => {
    expect(isIos(IPAD_AS_MAC, 0)).toBe(false)
    expect(isIos(ANDROID_CHROME, 5)).toBe(false)
  })
})
