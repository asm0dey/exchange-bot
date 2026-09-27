package fxbot

import java.math.BigDecimal
import java.math.MathContext

private val MC = MathContext.DECIMAL64
private val HUNDRED = BigDecimal(100)

/**
 * What the mini app says a person does. Offer and Bid are relative to the base currency,
 * but people type amounts in either currency, so the side alone does not tell a reader
 * what the person hands over. The verb they typed does: `/sell` quotes what you give.
 */
enum class Says { GIVES, WANTS }

fun saysOf(r: Request): Says =
    if (verbFor(r.side, r.statedCurrency, r.pair) == Verb.SELL) Says.GIVES else Says.WANTS

fun Says.verb(): Verb = if (this == Says.GIVES) Verb.SELL else Verb.BUY

fun otherLeg(pair: CurrencyPair, statedCurrency: String): String =
    if (statedCurrency == pair.base) pair.quote else pair.base

/**
 * The stated amount in the other leg at the reference rate — the notional turned round.
 * An estimate for reading, never a price; null when there is no rate to estimate with.
 */
fun approxOther(r: Request, rate: BigDecimal?): BigDecimal? {
    if (rate == null || rate.signum() <= 0) return null
    return if (r.statedCurrency == r.pair.base) r.statedAmount.multiply(rate, MC) else r.statedAmount.divide(rate, MC)
}

/** In the pair's base currency. [max] is null when the viewer accepts any size above [min]. */
data class AcceptedRange(val min: BigDecimal, val max: BigDecimal?)

/**
 * The viewer sizes that make a counterparty of [theirNotional], restated from
 * `findCounterparties`: their residual limit sets the floor, the viewer's own sets the
 * ceiling. `MiniAppViewsTest` pins the two together.
 */
fun acceptedRange(theirNotional: BigDecimal, theirPct: Int, minePct: Int): AcceptedRange {
    val floor = theirNotional.multiply(BigDecimal.ONE - BigDecimal(theirPct).divide(HUNDRED, MC), MC)
    val ceiling = if (minePct >= 100) null
        else theirNotional.divide(BigDecimal.ONE - BigDecimal(minePct).divide(HUNDRED, MC), MC)
    return AcceptedRange(floor, ceiling)
}

/** Service texts are Telegram HTML; the app shows them as text. */
fun plainText(html: String): String =
    html.replace(Regex("<[^>]+>"), "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
