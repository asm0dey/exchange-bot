package fxbot

import java.math.BigDecimal
import java.math.MathContext

private val MC = MathContext.DECIMAL64
private val HUNDRED = BigDecimal(100)

/**
 * A request's size in the pair's base currency, or null when it cannot be known —
 * a quote-denominated amount with no reference rate available.
 */
fun notional(r: Request, rate: BigDecimal?): BigDecimal? = when {
    r.statedCurrency == r.pair.base -> r.statedAmount
    // A missing rate and a nonsensical one mean the same thing here: size unknown.
    // Dividing by zero would throw, and a negative rate would yield a negative size.
    rate == null || rate.signum() <= 0 -> null
    else -> r.statedAmount.divide(rate, MC)
}

data class Counterparty(val request: Request, val notional: BigDecimal?, val distance: BigDecimal)

/**
 * Every resting request in the same chat that is on the opposite side and close
 * enough in size, closest first. Reserves nothing (ADR 0001).
 *
 * With no reference rate, only requests quoted in the same currency as the
 * subject can be compared — that comparison needs no conversion.
 */
fun findCounterparties(
    subject: Request,
    resting: List<Request>,
    rate: BigDecimal?,
    tolerancePct: Int,
    limit: Int = 5,
): List<Counterparty> {
    if (subject.state != RequestState.OPEN) return emptyList()
    val limitFraction = BigDecimal(tolerancePct).divide(HUNDRED, MC)
    return resting.asSequence()
        .filter { it.chatId == subject.chatId }
        .filter { it.pair == subject.pair }
        .filter { it.state == RequestState.OPEN }
        .filter { it.side != subject.side }
        .filter { it.userId != subject.userId }
        .mapNotNull { candidate ->
            val (a, b) = comparableSizes(subject, candidate, rate) ?: return@mapNotNull null
            val larger = a.max(b)
            if (larger.signum() == 0) return@mapNotNull null
            val distance = (a - b).abs().divide(larger, MC)
            if (distance > limitFraction) null
            else Counterparty(candidate, notional(candidate, rate), distance)
        }
        .sortedBy { it.distance }
        .take(limit)
        .toList()
}

/**
 * The two magnitudes to compare. Prefers notionals; falls back to raw stated
 * amounts when both requests are quoted in the same currency and no rate exists.
 */
private fun comparableSizes(
    subject: Request,
    candidate: Request,
    rate: BigDecimal?,
): Pair<BigDecimal, BigDecimal>? {
    val a = notional(subject, rate)
    val b = notional(candidate, rate)
    if (a != null && b != null) return a to b
    if (subject.statedCurrency == candidate.statedCurrency) {
        return subject.statedAmount to candidate.statedAmount
    }
    return null
}
