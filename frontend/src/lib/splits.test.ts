import { describe, expect, it } from 'vitest'
import vectors from '../../../api/test-vectors/splits.json'
import {
  divide,
  formatBasisPoints,
  parsePercent,
  parseShares,
  percentInputText,
  roundedUp,
  type SplitMethod,
} from './splits'

type Vector = {
  name: string
  total: number
  method: SplitMethod
  inputs: { member: string; input: number }[]
  shares?: { member: string; share: number }[]
  error?: string
}

describe('divide', () => {
  // The same cases the backend's SplitsTests run (#84): the preview must show the cents the API
  // records, and refuse what it refuses with the same code.
  it.each((vectors.cases as Vector[]).map((vector) => [vector.name, vector]))(
    '%s',
    (_, vector) => {
      const result = divide(
        BigInt(vector.total),
        vector.method,
        vector.inputs.map(({ member, input }) => ({
          member,
          value: BigInt(input),
        })),
      )

      if (vector.error !== undefined) {
        expect(result).toEqual({ ok: false, error: vector.error })
      } else {
        expect(result.ok).toBe(true)
        expect(
          result.ok &&
            result.shares.map(({ member, share }) => ({
              member,
              share: Number(share),
            })),
        ).toEqual(vector.shares)
      }
    },
  )

  it('gives everyone a weight of 1 when splitting equally, whatever was typed', () => {
    const result = divide(100n, 'EQUALLY', [
      { member: 'a', value: 5n },
      { member: 'b', value: 1n },
    ])

    expect(result.ok && result.shares.map((share) => share.share)).toEqual([
      50n,
      50n,
    ])
  })

  it('refuses a total of zero, which the dialog never splits', () => {
    expect(() => divide(0n, 'EQUALLY', [{ member: 'a', value: 1n }])).toThrow(
      RangeError,
    )
  })
})

describe('roundedUp', () => {
  it('names who got the cents left over', () => {
    const result = divide(1000n, 'EQUALLY', [
      { member: 'b', value: 1n },
      { member: 'a', value: 1n },
      { member: 'c', value: 1n },
    ])

    expect(result.ok && roundedUp(1000n, 'EQUALLY', result.shares)).toEqual([
      'a',
    ])
  })

  it('names nobody when the total divides exactly, or for exact amounts', () => {
    const shares = divide(1000n, 'SHARES', [
      { member: 'a', value: 2n },
      { member: 'b', value: 2n },
    ])
    const exact = divide(1000n, 'EXACT_AMOUNTS', [
      { member: 'a', value: 999n },
      { member: 'b', value: 1n },
    ])

    expect(shares.ok && roundedUp(1000n, 'SHARES', shares.shares)).toEqual([])
    expect(exact.ok && roundedUp(1000n, 'EXACT_AMOUNTS', exact.shares)).toEqual(
      [],
    )
  })
})

describe('parsePercent', () => {
  it('reads percentages as basis points, with either separator', () => {
    expect(parsePercent('33,33')).toBe(3333n)
    expect(parsePercent('33.33')).toBe(3333n)
    expect(parsePercent('50')).toBe(5000n)
    expect(parsePercent(' 12,5 ')).toBe(1250n)
    expect(parsePercent(',5')).toBe(50n)
  })

  it('refuses what is not a percentage with at most two decimals', () => {
    expect(parsePercent('')).toBeUndefined()
    expect(parsePercent('33,333')).toBeUndefined()
    expect(parsePercent('abc')).toBeUndefined()
    expect(parsePercent('-5')).toBeUndefined()
    expect(parsePercent(',')).toBeUndefined()
  })
})

describe('parseShares', () => {
  it('reads whole numbers only', () => {
    expect(parseShares('2')).toBe(2n)
    expect(parseShares(' 10 ')).toBe(10n)
    expect(parseShares('1.5')).toBeUndefined()
    expect(parseShares('')).toBeUndefined()
  })
})

describe('percentInputText and formatBasisPoints', () => {
  it('write basis points back in each language', () => {
    expect(percentInputText(3333n, 'pt-PT')).toBe('33,33')
    expect(percentInputText(3333n, 'en')).toBe('33.33')
    expect(percentInputText(5000n, 'en')).toBe('50')
    expect(percentInputText(1205n, 'en')).toBe('12.05')
    expect(formatBasisPoints(3333n, 'en')).toBe('33.33%')
    expect(formatBasisPoints(5000n, 'en')).toBe('50%')
    expect(formatBasisPoints(10n, 'en')).toBe('0.1%')
  })
})
