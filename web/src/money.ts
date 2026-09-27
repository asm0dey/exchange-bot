import type { RangeDto, Says } from './api'

const group = (s: string) => s.replace(/\B(?=(\d{3})+(?!\d))/g, ' ')

/** "45 000", "12.5": grouped with a thin space, at most 2 decimals. */
export function fmt(n: number): string {
  const [i, d] = (Math.round(n * 100) / 100).toString().split('.')
  return d ? `${group(i)}.${d}` : group(i)
}

/** An estimate: 3 significant digits from 1000 up, whole units below. */
export function approx(n: number): string {
  if (n >= 1000) {
    const p = 10 ** (Math.floor(Math.log10(n)) - 2)
    return fmt(Math.round(n / p) * p)
  }
  return fmt(Math.round(n))
}

export const flip = (s: Says): Says => (s === 'GIVES' ? 'WANTS' : 'GIVES')

/** True when this person hands over the pair's base currency (an offer). */
export const givesBase = (says: Says, currency: string, base: string) => (currency === base) === (says === 'GIVES')

export function toBase(amount: number, typed: string, base: string, rate: number | null): number | null {
  if (typed === base) return amount
  return rate ? amount / rate : null
}

export function toTyped(baseAmount: number, typed: string, base: string, rate: number | null): number | null {
  if (typed === base) return baseAmount
  return rate ? baseAmount * rate : null
}

/** The range in the typed currency, rounded inward so "fits" never overstates. */
export function rangeIn(r: RangeDto, typed: string, base: string, rate: number | null) {
  const min = toTyped(Number(r.min), typed, base, rate)
  if (min === null) return null
  const max = r.max == null ? null : toTyped(Number(r.max), typed, base, rate)
  return { min: Math.ceil(min), max: max === null ? null : Math.floor(max) }
}

export function fits(amount: number, typed: string, base: string, rate: number | null, r: RangeDto): boolean | null {
  const b = toBase(amount, typed, base, rate)
  if (b === null) return null
  return b >= Number(r.min) && (r.max == null || b <= Number(r.max))
}

export type SheetCandidate = { label: string; says: Says; currency: string; base: string; quote: string; range: RangeDto; rate: number | null }
export type MatchedBand = { label: string; shown: { min: number; max: number | null } | null; ok: boolean | null }

/**
 * Everyone resting on the other side of what's being typed, with the range each accepts shown
 * in the typed currency. Side and range are judged against each candidate's OWN base — the
 * server canonicalises pairs, so a card's `range` is always in that card's own base — which
 * makes the result independent of how the sheet's own pair happens to be ordered.
 */
export function matchCandidates(candidates: SheetCandidate[], mySays: Says, myCurrency: string, myAmount: number | null): MatchedBand[] {
  return candidates
    .filter((c) => givesBase(c.says, c.currency, c.base) !== givesBase(mySays, myCurrency, c.base))
    .map((c) => ({
      label: c.label,
      shown: rangeIn(c.range, myCurrency, c.base, c.rate),
      ok: myAmount === null ? null : fits(myAmount, myCurrency, c.base, c.rate, c.range),
    }))
}

/**
 * Which currencies the amount may be typed in. Pre-filled from a card, the toggle only
 * changes which leg is typed: giving means typing the leg the counterparty wants/gives in
 * their own terms, wanting means typing the other leg — so the counterparty's own side is
 * never on offer.
 */
export function allowedCurrencies(
  prefill: { says: Says; currency: string } | null, base: string, quote: string, toggle: Says,
): string[] {
  if (!prefill) return [base, quote]
  const other = prefill.currency === base ? quote : base
  const mySays = flip(prefill.says)
  return [toggle === mySays ? prefill.currency : other]
}
