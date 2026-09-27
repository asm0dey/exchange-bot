import { describe, expect, it } from 'vitest'
import { allowedCurrencies, approx, fits, flip, fmt, rangeIn, toBase } from './money'

describe('money', () => {
  it('groups thousands with a thin space', () => {
    expect(fmt(45000)).toBe('45 000')
    expect(fmt(950)).toBe('950')
  })
  it('rounds estimates to 3 significant digits from 1000 up', () => {
    expect(approx(89414)).toBe('89 400')
    expect(approx(478.1)).toBe('478')
  })
  it('flips the side', () => {
    expect(flip('GIVES')).toBe('WANTS')
  })
  it('converts a typed amount into the base only with a rate', () => {
    expect(toBase(89400, 'RUB', 'EUR', 94.12)).toBeCloseTo(949.85, 1)
    expect(toBase(950, 'EUR', 'EUR', null)).toBe(950)
    expect(toBase(89400, 'RUB', 'EUR', null)).toBeNull()
  })
  it("shows Marko's range in whichever currency is typed", () => {
    const r = { min: '760', max: '1187.5' }
    expect(rangeIn(r, 'EUR', 'EUR', 94.12)).toEqual({ min: 760, max: 1187 })
    expect(rangeIn(r, 'RUB', 'EUR', 94.12)).toEqual({ min: 71532, max: 111767 })
    expect(rangeIn(r, 'RUB', 'EUR', null)).toBeNull()
  })
  it('marks fitting and not fitting, and says nothing without a rate', () => {
    const r = { min: '760', max: '1187.5' }
    expect(fits(950, 'EUR', 'EUR', 94.12, r)).toBe(true)
    expect(fits(700, 'EUR', 'EUR', 94.12, r)).toBe(false)
    expect(fits(89400, 'RUB', 'EUR', null, r)).toBeNull()
    expect(fits(5000, 'EUR', 'EUR', null, { min: '760' })).toBe(true)
  })
  it("never offers the counterparty's own side on a pre-filled sheet", () => {
    // Marko WANTS EUR, so I GIVE EUR — or, flipped, I WANT RUB. Never "I want EUR".
    const prefill = { says: 'WANTS' as const, currency: 'EUR' }
    expect(allowedCurrencies(prefill, 'EUR', 'RUB', 'GIVES')).toEqual(['EUR'])
    expect(allowedCurrencies(prefill, 'EUR', 'RUB', 'WANTS')).toEqual(['RUB'])
    expect(allowedCurrencies(null, 'EUR', 'RUB', 'WANTS')).toEqual(['EUR', 'RUB'])
  })
})
