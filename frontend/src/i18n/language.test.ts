import { afterEach, describe, expect, it, vi } from 'vitest'
import { browserLanguage, initialLanguage, storeLanguage } from './language'

describe('browserLanguage', () => {
  it('follows the first preferred language MoneyTrail speaks', () => {
    expect(browserLanguage(['pt-PT', 'en'])).toBe('pt-PT')
    expect(browserLanguage(['en-GB', 'pt-PT'])).toBe('en')
    expect(browserLanguage(['fr-FR', 'pt', 'en'])).toBe('pt-PT')
  })

  it('gives European Portuguese to every Portuguese', () => {
    expect(browserLanguage(['pt-BR'])).toBe('pt-PT')
    expect(browserLanguage(['PT'])).toBe('pt-PT')
  })

  it('falls back to English', () => {
    expect(browserLanguage(['de-DE', 'fr'])).toBe('en')
    expect(browserLanguage([])).toBe('en')
  })
})

describe('initialLanguage', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('follows the browser on the first visit', () => {
    vi.spyOn(navigator, 'languages', 'get').mockReturnValue(['pt-PT', 'en'])

    expect(initialLanguage()).toBe('pt-PT')
  })

  it('keeps the language picked on an earlier visit', () => {
    vi.spyOn(navigator, 'languages', 'get').mockReturnValue(['pt-PT', 'en'])
    storeLanguage('en')

    expect(initialLanguage()).toBe('en')
  })

  it('ignores a stored value that is not a language MoneyTrail speaks', () => {
    vi.spyOn(navigator, 'languages', 'get').mockReturnValue(['pt-PT'])
    localStorage.setItem('moneytrail.language', 'xx')

    expect(initialLanguage()).toBe('pt-PT')
  })

  it('still works when the browser blocks storage', () => {
    vi.spyOn(navigator, 'languages', 'get').mockReturnValue(['pt-PT'])
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('Blocked', 'SecurityError')
    })

    expect(initialLanguage()).toBe('pt-PT')
  })
})
