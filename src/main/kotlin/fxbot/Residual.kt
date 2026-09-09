package fxbot

import java.math.BigDecimal
import java.math.MathContext

private val MC = MathContext.DECIMAL64

/**
 * What is left of [mine] after a done against [theirs], in [mine]'s own stated currency,
 * or null when there is nothing to state.
 *
 * A done closes both requests even when one side has size left over, because the
 * alternative — reducing a stated amount and leaving the request resting — would have the
 * bot publishing a number nobody typed (ADR 0003, ADR 0006). The residual is therefore a
 * NEW thing the person states, computed here at press time from the two closed rows, and
 * the original's stated amount is never rewritten.
 *
 * Null when the residual is zero or negative (the smaller side has none), and when the
 * two are stated in different currencies with no reference rate to bridge them.
 */
fun residualOf(mine: Request, theirs: Request, rate: BigDecimal?): BigDecimal? {
    if (mine.statedCurrency == theirs.statedCurrency) {
        return (mine.statedAmount - theirs.statedAmount).takeIf { it.signum() > 0 }
    }
    if (rate == null || rate.signum() <= 0) return null
    val a = notional(mine, rate) ?: return null
    val b = notional(theirs, rate) ?: return null
    val leftInBase = a - b
    if (leftInBase.signum() <= 0) return null
    // Back into the presser's own words: base amounts are already there, a quote amount
    // is the base figure at the reference rate.
    return if (mine.statedCurrency == mine.pair.base) leftInBase else leftInBase.multiply(rate, MC)
}

/**
 * Where a restated residual goes. A request born of an interest stated privately — the
 * bot-side row itself, or any of its showings — is restated as a new interest and fans
 * out like any other. A request TYPED in a group is restated in that group alone:
 * fanning it out bot-wide would put someone on the bot-side who never asked, and
 * consent is what puts them there (ADR 0007).
 */
fun restateGoesPrivate(mine: Request): Boolean = mine.interestToken != null || mine.chatId == NO_CHAT_ID
