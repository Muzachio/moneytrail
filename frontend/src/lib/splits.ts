import type { components } from '@/api/schema'
import { parseMoneyInput } from '@/lib/money'

export type SplitMethod = components['schemas']['SplitMethod']
type NewSplitMember = components['schemas']['NewSplitMember']

/** The methods in the order the dialog offers them, as in the design canvas. */
export const splitMethods = [
  'EQUALLY',
  'EXACT_AMOUNTS',
  'PERCENTAGES',
  'SHARES',
] as const satisfies readonly SplitMethod[]

/** Percentages are basis points: 10 000 is 100 %. */
export const WHOLE = 10_000n

/** The most shares may add up to, as the backend allows (`Splits.MAX_WEIGHT`). */
export const MAX_WEIGHT = 10_000n

/** What one person was given: their shares, their percentage in basis points, or their amount. */
export type SplitInput = { member: string; value: bigint }

/** One person's part of the total, in minor units. */
export type SplitShare = SplitInput & { share: bigint }

/** The codes the API refuses a split with (`GroupExpenseRefused`), checked in the same order. */
export type SplitError =
  | 'split_without_members'
  | 'split_member_twice'
  | 'split_input_not_positive'
  | 'split_percentages_not_100'
  | 'split_amounts_not_total'
  | 'split_too_many_parts'

export type SplitResult =
  { ok: true; shares: SplitShare[] } | { ok: false; error: SplitError }

/**
 * Ties go to the id that comes first as text, as in the backend (`Splits.BY_ID_AS_TEXT`): ids are
 * lowercase ASCII, so JavaScript's `<` on strings agrees with Java's `String.compareTo`.
 */
function byIdAsText(a: SplitInput, b: SplitInput) {
  return a.member < b.member ? -1 : a.member > b.member ? 1 : 0
}

/**
 * Divides `total` minor units among `inputs` exactly as the backend does (ADR-0012, the backend's
 * `Splits`): equally, by shares and by percentages through the largest-remainder method, exact
 * amounts as they are. The preview must show the cents the API will record, so everything is
 * `BigInt`: no float ever holds an amount, and `total × weight` cannot overflow.
 *
 * The shares come back in the order of the members' ids as text, as the API lists them. Splitting
 * `EQUALLY` gives everyone a weight of 1 whatever their `value`.
 */
export function divide(
  total: bigint,
  method: SplitMethod,
  inputs: readonly SplitInput[],
): SplitResult {
  if (total <= 0n) {
    throw new RangeError(`A split needs a total above zero, not ${total}`)
  }
  const ordered = inputs
    .map((input) =>
      method === 'EQUALLY' ? { member: input.member, value: 1n } : input,
    )
    .toSorted(byIdAsText)
  const error = check(total, method, ordered)
  if (error !== undefined) {
    return { ok: false, error }
  }
  const shares =
    method === 'EXACT_AMOUNTS'
      ? ordered.map((input) => input.value)
      : largestRemainder(total, ordered)
  return {
    ok: true,
    shares: ordered.map((input, index) => ({ ...input, share: shares[index] })),
  }
}

function check(
  total: bigint,
  method: SplitMethod,
  inputs: readonly SplitInput[],
): SplitError | undefined {
  if (inputs.length === 0) {
    return 'split_without_members'
  }
  const seen = new Set<string>()
  for (const input of inputs) {
    if (seen.has(input.member)) {
      return 'split_member_twice'
    }
    seen.add(input.member)
    if (input.value <= 0n) {
      return 'split_input_not_positive'
    }
  }
  const sum = sumOf(inputs)
  switch (method) {
    case 'EQUALLY':
    case 'SHARES':
      return sum > MAX_WEIGHT ? 'split_too_many_parts' : undefined
    case 'PERCENTAGES':
      return sum !== WHOLE ? 'split_percentages_not_100' : undefined
    case 'EXACT_AMOUNTS':
      return sum !== total ? 'split_amounts_not_total' : undefined
  }
}

/**
 * Each person gets `⌊T × w / W⌋`, and the minor units left over, fewer than the number of people,
 * go one each to the largest remainders, equal ones in id order (the sort is stable and the inputs
 * are in id order already).
 */
function largestRemainder(total: bigint, ordered: readonly SplitInput[]) {
  const weights = sumOf(ordered)
  const shares = ordered.map((input) => (total * input.value) / weights)
  const remainders = ordered.map((input) => (total * input.value) % weights)
  let leftOver = total - shares.reduce((sum, share) => sum + share, 0n)
  const byRemainder = ordered
    .map((_, index) => index)
    .toSorted((a, b) =>
      remainders[b] > remainders[a]
        ? 1
        : remainders[b] < remainders[a]
          ? -1
          : 0,
    )
  for (const index of byRemainder) {
    if (leftOver === 0n) {
      break
    }
    shares[index] += 1n
    leftOver -= 1n
  }
  return shares
}

/**
 * The members who got one of the minor units left over, for the note under a split that did not
 * divide exactly: their share is above `⌊T × w / W⌋`. None for exact amounts.
 */
export function roundedUp(
  total: bigint,
  method: SplitMethod,
  shares: readonly SplitShare[],
): string[] {
  if (method === 'EXACT_AMOUNTS' || shares.length === 0) {
    return []
  }
  const weighted = shares.map((share) => ({
    ...share,
    value: method === 'EQUALLY' ? 1n : share.value,
  }))
  const weights = sumOf(weighted)
  return weighted
    .filter((share) => share.share > (total * share.value) / weights)
    .map((share) => share.member)
}

function sumOf(inputs: readonly SplitInput[]) {
  return inputs.reduce((sum, input) => sum + input.value, 0n)
}

// Digits, then optionally one separator and up to two more digits: "33,33", "33.3", "50" or ",5".
const PERCENT = /^(\d*)(?:[.,](\d*))?$/

/**
 * A typed percentage as basis points: `33,33` and `33.33` are 3 333, `50` is 5 000. Read as text,
 * never with `parseFloat`; more than two decimals, or nothing typed, is undefined.
 */
export function parsePercent(text: string): bigint | undefined {
  const match = PERCENT.exec(text.trim())
  if (match === null) {
    return undefined
  }
  const [, whole, fraction = ''] = match
  if ((whole === '' && fraction === '') || fraction.length > 2) {
    return undefined
  }
  return BigInt((whole === '' ? '0' : whole) + fraction.padEnd(2, '0'))
}

/** A typed number of shares: a whole number, such as 2 for a couple. Undefined otherwise. */
export function parseShares(text: string): bigint | undefined {
  const typed = text.trim()
  return /^\d+$/.test(typed) ? BigInt(typed) : undefined
}

/**
 * Basis points as a person would type them back in `language`: 3 333 is `33,33` in Portuguese and
 * `33.33` in English, 5 000 is `50`. For a dialog that starts filled in.
 */
export function percentInputText(basisPoints: bigint, language: string) {
  const whole = basisPoints / 100n
  const fraction = basisPoints % 100n
  if (fraction === 0n) {
    return String(whole)
  }
  const separator =
    new Intl.NumberFormat(language)
      .formatToParts(1.5)
      .find((part) => part.type === 'decimal')?.value ?? '.'
  return `${whole}${separator}${String(fraction).padStart(2, '0')}`
}

/** Basis points as a percentage for `language`: 3 333 is "33.33%" or "33,33 %", 5 000 "50%". */
export function formatBasisPoints(basisPoints: bigint, language: string) {
  const whole = basisPoints / 100n
  const fraction = basisPoints % 100n
  // A decimal string, read exactly by Intl.NumberFormat (ES2023), never a float.
  const text = `${basisPoints < 0n ? '-' : ''}${whole < 0n ? -whole : whole}.${String(fraction < 0n ? -fraction : fraction).padStart(2, '0')}`
  return new Intl.NumberFormat(language, {
    style: 'unit',
    unit: 'percent',
    maximumFractionDigits: 2,
  }).format(text as `${number}`)
}

/** One row of the dialog's "Who takes part": whether the person is ticked, and what is typed. */
export type SplitRow = { member: string; taking: boolean; typed: string }

/** Why the split cannot be saved yet, for the line under the table. */
export type SplitProblem =
  | { kind: 'amount' }
  | { kind: 'nobody' }
  | { kind: 'shares' }
  | { kind: 'tooManyShares' }
  | { kind: 'percent' }
  | { kind: 'percentLeft'; basisPoints: bigint }
  | { kind: 'percentOver'; basisPoints: bigint }
  | { kind: 'exactMissing' }
  | { kind: 'amountLeft'; amountMinor: bigint }
  | { kind: 'amountOver'; amountMinor: bigint }

/** The total of what is typed in the table: people, shares, basis points or minor units. */
export type SplitInputTotal =
  | { kind: 'people'; count: number }
  | { kind: 'shares'; sum: bigint }
  | { kind: 'percent'; basisPoints: bigint }
  | { kind: 'amount'; amountMinor: bigint }

export type SplitPreview = {
  /** The amount typed, in minor units, when it is one above zero. */
  total: bigint | undefined
  /** What each ticked person pays, as far as it can be known yet. */
  shares: Map<string, bigint>
  inputTotal: SplitInputTotal | undefined
} & (
  { ok: true; members: NewSplitMember[] } | { ok: false; problem: SplitProblem }
)

/**
 * What the dialog shows as the person types: each share as the API will record it (`divide`), the
 * total of the inputs, and, until the split adds up, what is missing. The checks come in the order
 * the design canvas shows them.
 */
export function splitPreview(
  amountText: string,
  currency: string,
  method: SplitMethod,
  rows: readonly SplitRow[],
): SplitPreview {
  const shares = new Map<string, bigint>()
  const amount = parseMoneyInput(amountText, currency)
  const total =
    amount.ok && amount.amountMinor > 0 ? BigInt(amount.amountMinor) : undefined
  const taking = rows.filter((row) => row.taking)
  const fail = (
    problem: SplitProblem,
    inputTotal?: SplitInputTotal,
  ): SplitPreview => ({
    ok: false,
    problem,
    total,
    shares,
    inputTotal,
  })

  if (method === 'EXACT_AMOUNTS') {
    const typed = taking.map((row) => {
      const parsed = parseMoneyInput(row.typed, currency)
      return {
        member: row.member,
        value: parsed.ok ? BigInt(parsed.amountMinor) : undefined,
      }
    })
    for (const { member, value } of typed) {
      if (value !== undefined) {
        shares.set(member, value)
      }
    }
    const sum = typed.reduce((all, { value }) => all + (value ?? 0n), 0n)
    const inputTotal: SplitInputTotal = { kind: 'amount', amountMinor: sum }
    if (total === undefined) {
      // What was typed still adds up below the table, before the amount is there.
      return fail({ kind: 'amount' }, inputTotal)
    }
    if (taking.length === 0) {
      return fail({ kind: 'nobody' }, inputTotal)
    }
    if (sum < total) {
      return fail({ kind: 'amountLeft', amountMinor: total - sum }, inputTotal)
    }
    if (sum > total) {
      return fail({ kind: 'amountOver', amountMinor: sum - total }, inputTotal)
    }
    if (typed.some(({ value }) => value === undefined || value <= 0n)) {
      return fail({ kind: 'exactMissing' }, inputTotal)
    }
    return settle(
      total,
      method,
      typed as SplitInput[],
      inputTotal,
      shares,
      fail,
    )
  }

  if (total === undefined) {
    return fail({ kind: 'amount' })
  }
  if (taking.length === 0) {
    return fail({ kind: 'nobody' })
  }
  if (method === 'EQUALLY') {
    return settle(
      total,
      method,
      taking.map((row) => ({ member: row.member, value: 1n })),
      { kind: 'people', count: taking.length },
      shares,
      fail,
    )
  }

  const parse = method === 'SHARES' ? parseShares : parsePercent
  const weights = taking.map((row) => ({
    member: row.member,
    value: parse(row.typed),
  }))
  const sum = weights.reduce((all, { value }) => all + (value ?? 0n), 0n)
  const missing = weights.some(
    ({ value }) => value === undefined || value <= 0n,
  )
  if (method === 'SHARES') {
    const inputTotal: SplitInputTotal = { kind: 'shares', sum }
    if (missing) {
      return fail({ kind: 'shares' }, inputTotal)
    }
    if (sum > MAX_WEIGHT) {
      return fail({ kind: 'tooManyShares' }, inputTotal)
    }
    return settle(
      total,
      method,
      weights as SplitInput[],
      inputTotal,
      shares,
      fail,
    )
  }
  const inputTotal: SplitInputTotal = { kind: 'percent', basisPoints: sum }
  if (missing) {
    return fail({ kind: 'percent' }, inputTotal)
  }
  if (sum < WHOLE) {
    return fail({ kind: 'percentLeft', basisPoints: WHOLE - sum }, inputTotal)
  }
  if (sum > WHOLE) {
    return fail({ kind: 'percentOver', basisPoints: sum - WHOLE }, inputTotal)
  }
  return settle(
    total,
    method,
    weights as SplitInput[],
    inputTotal,
    shares,
    fail,
  )
}

/** The split itself, once every input is there: the shares, and the members to send. */
function settle(
  total: bigint,
  method: SplitMethod,
  inputs: SplitInput[],
  inputTotal: SplitInputTotal,
  shares: Map<string, bigint>,
  fail: (problem: SplitProblem, inputTotal?: SplitInputTotal) => SplitPreview,
): SplitPreview {
  const result = divide(total, method, inputs)
  if (!result.ok) {
    // The checks above refuse everything `divide` does; this only keeps the two in step.
    return fail(
      { kind: method === 'PERCENTAGES' ? 'percent' : 'shares' },
      inputTotal,
    )
  }
  for (const { member, share } of result.shares) {
    shares.set(member, share)
  }
  return {
    ok: true,
    total,
    shares,
    inputTotal,
    members: result.shares.map(({ member, value }) =>
      method === 'EQUALLY'
        ? { userId: member }
        : // A safe integer by now: shares add up to 10 000 at most, percentages to 10 000, and
          // exact amounts to the total, which `parseMoneyInput` keeps within the safe range.
          { userId: member, value: Number(value) },
    ),
  }
}
