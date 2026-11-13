import { describe, expect, it } from 'vitest'
import { formatMoney, parseMoneyInput, sumMoney } from './money'

// Intl separates Portuguese groups and the euro sign with a no-break space (U+00A0).
const NBSP = ' '

describe('formatMoney', () => {
  it('writes euros the way each language does', () => {
    const money = { amountMinor: 1250, currency: 'EUR' }

    expect(formatMoney(money, 'pt-PT')).toBe(`12,50${NBSP}€`)
    expect(formatMoney(money, 'en')).toBe('€12.50')
  })

  it('writes negative amounts with a minus sign', () => {
    const money = { amountMinor: -1250, currency: 'EUR' }

    expect(formatMoney(money, 'pt-PT')).toBe(`−12,50${NBSP}€`)
    expect(formatMoney(money, 'en')).toBe('−€12.50')
    expect(formatMoney({ amountMinor: -5, currency: 'EUR' }, 'en')).toBe(
      '−€0.05',
    )
  })

  it('writes zero and single cents', () => {
    expect(formatMoney({ amountMinor: 0, currency: 'EUR' }, 'en')).toBe('€0.00')
    expect(formatMoney({ amountMinor: 7, currency: 'EUR' }, 'pt-PT')).toBe(
      `0,07${NBSP}€`,
    )
  })

  it('groups the thousands of large amounts', () => {
    const money = { amountMinor: 123_456_789, currency: 'EUR' }

    expect(formatMoney(money, 'pt-PT')).toBe(`1${NBSP}234${NBSP}567,89${NBSP}€`)
    expect(formatMoney(money, 'en')).toBe('€1,234,567.89')
  })

  it('keeps every cent of amounts a float cannot hold', () => {
    // As a float, 9007199254740987 / 100 is 90071992547409.875, which rounds to .88.
    expect(
      formatMoney(
        { amountMinor: 9_007_199_254_740_987, currency: 'EUR' },
        'en',
      ),
    ).toBe('€90,071,992,547,409.87')
    expect(
      formatMoney(
        { amountMinor: -Number.MAX_SAFE_INTEGER, currency: 'EUR' },
        'en',
      ),
    ).toBe('−€90,071,992,547,409.91')
  })

  it('uses the minor units of each currency', () => {
    expect(formatMoney({ amountMinor: 1250, currency: 'JPY' }, 'en')).toBe(
      '¥1,250',
    )
  })

  it('refuses amounts that are not whole minor units', () => {
    expect(() =>
      formatMoney({ amountMinor: 12.5, currency: 'EUR' }, 'en'),
    ).toThrow(RangeError)
    expect(() =>
      formatMoney(
        { amountMinor: Number.MAX_SAFE_INTEGER + 2, currency: 'EUR' },
        'en',
      ),
    ).toThrow(RangeError)
  })

  it('writes a plus sign before changes above zero when asked to', () => {
    const signed = { signed: true }

    expect(
      formatMoney({ amountMinor: 41029, currency: 'EUR' }, 'en', signed),
    ).toBe('+€410.29')
    expect(
      formatMoney({ amountMinor: 41029, currency: 'EUR' }, 'pt-PT', signed),
    ).toBe(`+410,29${NBSP}€`)
    expect(
      formatMoney({ amountMinor: -2270, currency: 'EUR' }, 'en', signed),
    ).toBe('−€22.70')
    expect(formatMoney({ amountMinor: 0, currency: 'EUR' }, 'en', signed)).toBe(
      '€0.00',
    )
    // The unsigned formatter is a different one, and stays without a plus.
    expect(formatMoney({ amountMinor: 41029, currency: 'EUR' }, 'en')).toBe(
      '€410.29',
    )
  })
})

describe('sumMoney', () => {
  it('adds amounts of one currency', () => {
    expect(
      sumMoney('EUR', [
        { amountMinor: 218437, currency: 'EUR' },
        { amountMinor: -31240, currency: 'EUR' },
      ]),
    ).toEqual({ amountMinor: 187197, currency: 'EUR' })
    expect(sumMoney('EUR', [])).toEqual({ amountMinor: 0, currency: 'EUR' })
  })

  it('stays exact up to the largest safe integer, and refuses totals past it', () => {
    const almost = { amountMinor: Number.MAX_SAFE_INTEGER - 1, currency: 'EUR' }
    const cent = { amountMinor: 1, currency: 'EUR' }

    expect(sumMoney('EUR', [almost, cent]).amountMinor).toBe(
      Number.MAX_SAFE_INTEGER,
    )
    // As floats, MAX_SAFE_INTEGER + 2 would come out as MAX_SAFE_INTEGER + 1.
    expect(() => sumMoney('EUR', [almost, cent, cent, cent])).toThrow(
      RangeError,
    )
    expect(() =>
      sumMoney('EUR', [
        { amountMinor: -Number.MAX_SAFE_INTEGER, currency: 'EUR' },
        { amountMinor: -1, currency: 'EUR' },
      ]),
    ).toThrow(RangeError)
  })

  it('refuses to add another currency without an exchange rate', () => {
    expect(() =>
      sumMoney('EUR', [{ amountMinor: 100, currency: 'USD' }]),
    ).toThrow(RangeError)
  })
})

describe('parseMoneyInput', () => {
  it('reads a comma or a point as the decimal separator', () => {
    expect(parseMoneyInput('12,50', 'EUR')).toEqual({
      ok: true,
      amountMinor: 1250,
    })
    expect(parseMoneyInput('12.50', 'EUR')).toEqual({
      ok: true,
      amountMinor: 1250,
    })
  })

  it('accepts whole amounts, one decimal and spaces around', () => {
    expect(parseMoneyInput('12', 'EUR')).toEqual({
      ok: true,
      amountMinor: 1200,
    })
    expect(parseMoneyInput('12,5', 'EUR')).toEqual({
      ok: true,
      amountMinor: 1250,
    })
    expect(parseMoneyInput(',5', 'EUR')).toEqual({ ok: true, amountMinor: 50 })
    expect(parseMoneyInput('12,', 'EUR')).toEqual({
      ok: true,
      amountMinor: 1200,
    })
    expect(parseMoneyInput(' 0,07 ', 'EUR')).toEqual({
      ok: true,
      amountMinor: 7,
    })
  })

  it('gets the cents right where parseFloat does not', () => {
    // parseFloat(text) * 100 gives 28.999…, 114.999…, 434.999… and 1998.999…, so cutting the
    // decimals off would lose a cent.
    for (const [text, amountMinor] of [
      ['0.29', 29],
      ['1.15', 115],
      ['4,35', 435],
      ['19,99', 1999],
    ] as const) {
      expect(parseMoneyInput(text, 'EUR')).toEqual({ ok: true, amountMinor })
    }
  })

  it('refuses more decimals than the currency has', () => {
    expect(parseMoneyInput('12,505', 'EUR')).toEqual({
      ok: false,
      error: 'too-many-decimals',
    })
    expect(parseMoneyInput('1.234', 'EUR')).toEqual({
      ok: false,
      error: 'too-many-decimals',
    })
    expect(parseMoneyInput('12,5', 'JPY')).toEqual({
      ok: false,
      error: 'too-many-decimals',
    })
    expect(parseMoneyInput('1250', 'JPY')).toEqual({
      ok: true,
      amountMinor: 1250,
    })
  })

  it('refuses what is not an amount', () => {
    for (const text of [
      'abc',
      ',',
      '12,50 €',
      '1.234,56',
      '1 234',
      '-12',
      '+12',
      '1e3',
      '١٢',
    ]) {
      expect(parseMoneyInput(text, 'EUR'), text).toEqual({
        ok: false,
        error: 'invalid',
      })
    }
    expect(parseMoneyInput('  ', 'EUR')).toEqual({ ok: false, error: 'empty' })
  })

  it('refuses amounts too large to stay exact', () => {
    expect(parseMoneyInput('90071992547409,91', 'EUR')).toEqual({
      ok: true,
      amountMinor: Number.MAX_SAFE_INTEGER,
    })
    expect(parseMoneyInput('90071992547409,92', 'EUR')).toEqual({
      ok: false,
      error: 'too-large',
    })
    expect(parseMoneyInput('9'.repeat(40), 'EUR')).toEqual({
      ok: false,
      error: 'too-large',
    })
  })
})
