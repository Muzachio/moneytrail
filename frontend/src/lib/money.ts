import type { components } from '@/api/schema'

/**
 * An amount as the API sends it (architecture.md, "API"): integer minor units of an ISO 4217
 * currency, so `{ amountMinor: 1250, currency: 'EUR' }` is €12.50. Amounts stay integers from the
 * API to the screen and back; only text ever has a decimal point.
 */
export type Money = components['schemas']['Money']

// U+2212, as in the design canvas: as wide as "+" and the digits, so columns of amounts line up.
const MINUS_SIGN = '−'

const formatters = new Map<string, Intl.NumberFormat>()

function currencyFormatter(language: string, currency: string, signed = false) {
  const key = `${language} ${currency} ${signed}`
  let formatter = formatters.get(key)
  if (formatter === undefined) {
    formatter = new Intl.NumberFormat(language, {
      style: 'currency',
      currency,
      signDisplay: signed ? 'exceptZero' : 'auto',
    })
    formatters.set(key, formatter)
  }
  return formatter
}

/**
 * How many minor units make up the major unit, as a power of ten: 2 for EUR, 0 for JPY, 3 for BHD.
 * The browser's CLDR data agrees with ISO 4217, which the backend's `java.util.Currency` uses, for
 * EUR, the only currency in M1.
 */
export function currencyDigits(currency: string): number {
  // Always set for a currency format; the type allows undefined for significant-digit rounding.
  return currencyFormatter('en', currency).resolvedOptions()
    .maximumFractionDigits!
}

/** 1250 with 2 digits → "12.50", -5 → "-0.05", 7 with 0 digits → "7": text only, no division. */
function decimalText(amountMinor: number, digits: number): `${number}` {
  const sign = amountMinor < 0 ? '-' : ''
  const units = String(Math.abs(amountMinor)).padStart(digits + 1, '0')
  const whole = units.slice(0, units.length - digits)
  const fraction = units.slice(units.length - digits)
  return `${sign}${whole}${digits > 0 ? `.${fraction}` : ''}` as `${number}`
}

/**
 * `money` written for people who read `language`: 1250 EUR is `12,50 €` in Portuguese and `€12.50`
 * in English.
 *
 * Intl.NumberFormat reads a decimal string exactly (ES2023), so the amount never becomes a binary
 * float on the way: `amountMinor / 100` would turn 9007199254740987 cents into €90,071,992,547,409.88.
 *
 * `signed` writes a plus sign before amounts above zero, for changes: `+€410.29`, `−€22.70`, but
 * `€0.00`.
 */
export function formatMoney(
  { amountMinor, currency }: Money,
  language: string,
  { signed = false } = {},
) {
  if (!Number.isSafeInteger(amountMinor)) {
    throw new RangeError(`Not a whole number of minor units: ${amountMinor}`)
  }
  return currencyFormatter(language, currency, signed)
    .formatToParts(decimalText(amountMinor, currencyDigits(currency)))
    .map((part) => (part.type === 'minusSign' ? MINUS_SIGN : part.value))
    .join('')
}

/**
 * The total of `amounts`, all in `currency`: net worth, or the balance of a group of accounts.
 *
 * Added as BigInt, so a total past Number.MAX_SAFE_INTEGER is refused rather than rounded to the
 * nearest float. Amounts of another currency are refused too: adding euros to dollars needs an
 * exchange rate, which M1 does not have.
 */
export function sumMoney(currency: string, amounts: readonly Money[]): Money {
  let total = 0n
  for (const money of amounts) {
    if (money.currency !== currency) {
      throw new RangeError(
        `Cannot add ${money.currency} to a total in ${currency}`,
      )
    }
    total += BigInt(money.amountMinor)
  }
  if (
    total > BigInt(Number.MAX_SAFE_INTEGER) ||
    total < -BigInt(Number.MAX_SAFE_INTEGER)
  ) {
    throw new RangeError(`The total of ${currency} amounts is too large`)
  }
  return { amountMinor: Number(total), currency }
}

/**
 * What a money input shows around the number in `language`: the currency symbol and its side (`€`
 * before the number in English, after it in Portuguese), and zero as a placeholder (`0.00`,
 * `0,00`).
 */
export function currencyLayout(currency: string, language: string) {
  const parts = currencyFormatter(language, currency).formatToParts(
    decimalText(0, currencyDigits(currency)),
  )
  const symbolAt = parts.findIndex((part) => part.type === 'currency')
  const numberAt = parts.findIndex((part) => part.type === 'integer')
  return {
    symbol: parts[symbolAt].value,
    symbolFirst: symbolAt < numberAt,
    zero: parts
      .filter(({ type }) => ['integer', 'decimal', 'fraction'].includes(type))
      .map(({ value }) => value)
      .join(''),
  }
}

/** Why what was typed is not an amount; each form says it in its own words. */
export type MoneyInputError =
  'empty' | 'invalid' | 'too-many-decimals' | 'too-large'

export type ParsedMoneyInput =
  { ok: true; amountMinor: number } | { ok: false; error: MoneyInputError }

// Digits, then optionally one separator and more digits: "12,50", "12.50", "12", "12," or ",5".
const AMOUNT = /^(\d*)(?:[.,](\d*))?$/

/**
 * What someone typed into a money input, as integer minor units of `currency`: `12,50` and
 * `12.50` are both 1250 EUR.
 *
 * - Either separator marks the decimals: phones show the decimal key of their region, so
 *   Portuguese people get a comma even in English.
 * - There are no thousands separators, so `1.234` is never quietly read as 1234: for EUR it has
 *   too many decimals, and the person sees it.
 * - More decimals than the currency has are refused, never rounded or cut.
 * - The digits are read as text: `parseFloat('0.29') * 100` is 28.999999999999996.
 * - No sign: amounts are typed as positive numbers; whether money comes in or goes out is the form's
 *   choice (expense, income).
 */
export function parseMoneyInput(
  text: string,
  currency: string,
): ParsedMoneyInput {
  const typed = text.trim()
  if (typed === '') {
    return { ok: false, error: 'empty' }
  }
  const match = AMOUNT.exec(typed)
  if (match === null) {
    return { ok: false, error: 'invalid' }
  }
  const [, whole, fraction = ''] = match
  if (whole === '' && fraction === '') {
    return { ok: false, error: 'invalid' }
  }
  const digits = currencyDigits(currency)
  if (fraction.length > digits) {
    return { ok: false, error: 'too-many-decimals' }
  }
  // BigInt reads any number of digits exactly; a JavaScript number holds integers exactly only up
  // to Number.MAX_SAFE_INTEGER, and the API's JSON arrives as numbers.
  const amountMinor = BigInt(whole + fraction.padEnd(digits, '0'))
  if (amountMinor > BigInt(Number.MAX_SAFE_INTEGER)) {
    return { ok: false, error: 'too-large' }
  }
  return { ok: true, amountMinor: Number(amountMinor) }
}
