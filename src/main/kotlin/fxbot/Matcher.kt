package fxbot

import java.math.BigDecimal
import java.math.MathContext

private val MC = MathContext.DECIMAL64
private val HUNDRED = BigDecimal(100)

internal const val TOLERANCE_HELP = "Give me a percentage between 1 and 100, like /tolerance 20"
internal fun parseTolerancePct(raw: String): Int? = raw.trim().toIntOrNull()?.takeIf { it in 1..100 }
internal fun toleranceSetReply(pct: Int): String =
    "A counterparty now matches when what they'd leave you is within $pct% of your own amount."

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
 * What is left of [mine] when [theirs] is smaller, as a fraction of [mine] — the
 * residual each side judges against its own size tolerance (ADR 0006). A counterparty
 * at least as big as you leaves you nothing, so your answer is always yes.
 */
private fun residualFraction(mine: BigDecimal, theirs: BigDecimal): BigDecimal =
    if (theirs >= mine) BigDecimal.ZERO else (mine - theirs).divide(mine, MC)

/**
 * Every resting request in the same chat that is on the opposite side and leaves each
 * side a residual it accepts, closest first. Reserves nothing (ADR 0001).
 *
 * Each side is judged separately against its own number: [tolerancePct] is the
 * subject's, [peerTolerancePct] answers for a candidate. In a chat both are that
 * chat's setting and this reduces to the old test exactly; with no chat each
 * person brings their own. Counterparties are strictly pairwise — the bot never
 * searches for a set that together covers a size (ADR 0006).
 *
 * With no reference rate, only requests quoted in the same currency as the subject can
 * be compared — that comparison needs no conversion.
 */
fun findCounterparties(
    subject: Request,
    resting: List<Request>,
    rate: BigDecimal?,
    tolerancePct: Int,
    limit: Int = 5,
    peerTolerancePct: (Request) -> Int = { tolerancePct },
): List<Counterparty> {
    if (subject.state != RequestState.OPEN) return emptyList()
    val mineLimit = BigDecimal(tolerancePct).divide(HUNDRED, MC)
    return resting.asSequence()
        .filter { it.chatId == subject.chatId }
        .filter { it.pair == subject.pair }
        .filter { it.state == RequestState.OPEN }
        .filter { it.side != subject.side }
        .filter { it.userId != subject.userId }
        .mapNotNull { candidate ->
            val (a, b) = comparableSizes(subject, candidate, rate) ?: return@mapNotNull null
            // A non-positive size cannot carry a residual: dividing by it would throw, and
            // parseAmount rejects zero and negatives, so this only guards a corrupt row.
            if (a.signum() <= 0 || b.signum() <= 0) return@mapNotNull null
            if (residualFraction(a, b) > mineLimit) return@mapNotNull null
            val theirLimit = BigDecimal(peerTolerancePct(candidate)).divide(HUNDRED, MC)
            if (residualFraction(b, a) > theirLimit) return@mapNotNull null
            // Ordering only. Acceptance was decided by the two residuals above.
            val distance = (a - b).abs().divide(a.max(b), MC)
            Counterparty(candidate, notional(candidate, rate), distance)
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
