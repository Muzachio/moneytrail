import { describe, expect, it } from 'vitest'
import { formatDate } from './dates'

describe('formatDate', () => {
  // Midday, so the day is the same in any time zone the tests run in.
  const day = '2026-06-03T12:00:00Z'

  it('writes the day before the month in English, as the design canvas does', () => {
    expect(formatDate(day, 'en')).toBe('3 June 2026')
  })

  it('writes the date the Portuguese way', () => {
    expect(formatDate(day, 'pt-PT')).toBe('3 de junho de 2026')
  })
})
