import { describe, expect, it } from 'vitest'
import {
  addDays,
  addMonths,
  formatDate,
  formatDay,
  formatMonth,
  formatWeekday,
  isDay,
  isMonth,
  today,
} from './dates'

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

describe('calendar days', () => {
  it('is today on the device’s calendar, not in UTC', () => {
    // 00:30 on 1 November where the person is, whatever UTC says.
    expect(today(new Date(2026, 10, 1, 0, 30))).toBe('2026-11-01')
  })

  it('moves across months and years', () => {
    expect(addDays('2026-03-01', -1)).toBe('2026-02-28')
    expect(addDays('2028-02-28', 1)).toBe('2028-02-29')
    expect(addMonths('2026-01', -1)).toBe('2025-12')
    expect(addMonths('2026-12', 1)).toBe('2027-01')
  })

  it('tells real days and months from other text', () => {
    expect(isDay('2026-02-28')).toBe(true)
    expect(isDay('2026-02-30')).toBe(false)
    expect(isDay('')).toBe(false)
    expect(isMonth('2026-10')).toBe(true)
    expect(isMonth('2026-13')).toBe(false)
  })

  it('writes a day as it is, in any time zone', () => {
    expect(
      formatDay('2026-10-01', 'en', { day: 'numeric', month: 'long' }),
    ).toBe('1 October')
    expect(formatMonth('2026-10', 'pt-PT')).toBe('outubro de 2026')
    expect(formatWeekday('2026-10-23', 'pt-PT')).toBe('Sexta-feira')
    expect(formatWeekday('2026-10-23', 'en')).toBe('Friday')
  })
})
