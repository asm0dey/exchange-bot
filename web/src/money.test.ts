import { describe, expect, it } from 'bun:test'
import { ago, allowedCurrencies, approx, fits, flip, fmt, givesBase, left, matchCandidates, parseAmount, rangeIn, toBase, type SheetCandidate } from './money'

describe('money', () => {
  it('groups thousands with a thin space', () => {
    expect(fmt(45000)).toBe('45 000')
    expect(fmt(950)).toBe('950')
  })
  it('rounds estimates to 3 significant digits from 1000 up', () => {
    expect(approx(89414)).toBe('89 400')
    expect(approx(478.1)).toBe('478')
  })
  it('says "1 day" not "1 days", and only drops to days at 24h (chat.json\'s five cards)', () => {
    const now = Date.parse('2026-09-27T12:00:00Z') // e2e/telegram.ts's NOW
    expect(ago(1790503200, now)).toBe('2 h') // a1
    expect(ago(1790492400, now)).toBe('5 h') // a4, Marko
    expect(ago(1790424000, now)).toBe('1 day') // a2, Jelena — exactly 24h
    expect(ago(1790337600, now)).toBe('2 days') // a5, @ana.p
    expect(ago(1790251200, now)).toBe('3 days') // a3, @tomas_k
  })
  // Mirrors MoneyTest.kt's "parses plain and grouped amounts" / "rejects amounts that are
  // not positive numbers" — the sheet's amount field must accept exactly what the bot does.
  it('parses plain and grouped amounts the same way the bot does', () => {
    expect(parseAmount('1000')).toBe(1000)
    expect(parseAmount('1 000')).toBe(1000)
    expect(parseAmount('1,000.50')).toBe(1000.5)
    expect(parseAmount('0.5')).toBe(0.5)
  })
  it('rejects amounts that are not positive numbers', () => {
    expect(parseAmount('0')).toBeNull()
    expect(parseAmount('-5')).toBeNull()
    expect(parseAmount('abc')).toBeNull()
    expect(parseAmount('')).toBeNull()
    expect(parseAmount('1e9')).toBeNull()
  })
  it('says "1 day left" not "1 days left", and "0 days left" once expired', () => {
    const now = Date.parse('2026-09-27T12:00:00Z') // e2e/telegram.ts's NOW
    expect(left(now / 1000 + 5 * 86400, now)).toBe('5 days left')
    expect(left(now / 1000 + 86400, now)).toBe('1 day left')
    expect(left(now / 1000 - 3600, now)).toBe('0 days left')
  })
  it('flips the side', () => {
    expect(flip('GIVES')).toBe('WANTS')
  })
  it('colours by who hands over the base currency, not by side alone', () => {
    expect(givesBase('GIVES', 'EUR', 'EUR')).toBe(true)
    expect(givesBase('WANTS', 'EUR', 'EUR')).toBe(false)
    expect(givesBase('GIVES', 'RUB', 'EUR')).toBe(false)
    expect(givesBase('WANTS', 'RUB', 'EUR')).toBe(true)
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

  // Browse holds these three (mockups.html, the composing-500-EUR worked example): a same-side
  // Gives 500 EUR, and two other-side cards, Gives 80 000 RUB and Wants 450 EUR. Every card's
  // range is in its own base, EUR — the pair's canonical base — regardless of what the composing
  // sheet's own base/quote happen to be.
  const rate = 94.12
  const givesEur: SheetCandidate = { label: 'Gives 500 EUR', says: 'GIVES', currency: 'EUR', base: 'EUR', quote: 'RUB', range: { min: '400', max: '625' }, rate }
  const givesRub: SheetCandidate = { label: 'Gives 80 000 RUB', says: 'GIVES', currency: 'RUB', base: 'EUR', quote: 'RUB', range: { min: '680', max: '1062' }, rate }
  const wantsEur: SheetCandidate = { label: 'Wants 450 EUR', says: 'WANTS', currency: 'EUR', base: 'EUR', quote: 'RUB', range: { min: '360', max: '562' }, rate }

  it('filters out the same side and counts fits when giving 500 EUR', () => {
    const result = matchCandidates([givesEur, givesRub, wantsEur], 'GIVES', 'EUR', 500)
    expect(result).toHaveLength(2)
    expect(result.some((b) => b.label === givesEur.label)).toBe(false)
    expect(result.filter((b) => b.ok).length).toBe(1)
    expect(result.find((b) => b.label === wantsEur.label)?.ok).toBe(true)
    expect(result.find((b) => b.label === givesRub.label)?.ok).toBe(false)
  })

  it('excludes the same-side card when wanting EUR', () => {
    const result = matchCandidates([givesEur, givesRub, wantsEur], 'WANTS', 'EUR', 500)
    expect(result.some((b) => b.label === wantsEur.label)).toBe(false)
  })

  it("gives the same candidates and fit verdicts whichever way the sheet's own pair is ordered", () => {
    const cards = [givesEur, givesRub, wantsEur]
    // Mirrors PrivateView's own symmetric pair filter — the candidates it hands to
    // matchCandidates are unaffected by which of the sheet's base/quote is which.
    const byPair = (base: string, quote: string) => cards.filter((c) => (c.base === base && c.quote === quote) || (c.base === quote && c.quote === base))
    const asEurRub = matchCandidates(byPair('EUR', 'RUB'), 'GIVES', 'EUR', 500)
    const asRubEur = matchCandidates(byPair('RUB', 'EUR'), 'GIVES', 'EUR', 500)
    expect(asEurRub).toEqual(asRubEur)
    expect(asEurRub).toHaveLength(2)
    expect(asEurRub.filter((b) => b.ok).length).toBe(1)
  })

  it('has no shown range for a cross-currency candidate without a rate, but keeps a same-currency one', () => {
    const noRate: SheetCandidate = { ...wantsEur, rate: null }
    // Typed in RUB: cross-currency against this candidate's EUR base, and no rate to convert with.
    const cross = matchCandidates([noRate], 'WANTS', 'RUB', null)
    expect(cross[0]?.shown ?? null).toBeNull()
    // Typed in EUR: same currency as this candidate's base, so no rate is needed at all.
    const same = matchCandidates([noRate], 'GIVES', 'EUR', null)
    expect(same[0]?.shown).toEqual({ min: 360, max: 562 })
  })
})
