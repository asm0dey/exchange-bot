package fxbot

import java.math.BigDecimal
import java.time.Instant

enum class RequestState {
    OPEN, DONE, CANCELLED, EXPIRED;

    val isTerminal get() = this != OPEN
}

/**
 * The chat id a no-names request carries. Telegram never issues 0, and `Matcher`
 * already filters on `chatId ==`, so the two surfaces cannot meet by accident.
 */
const val NO_NAMES_CHAT_ID = 0L

/**
 * Someone's stated willingness to exchange. Amounts are held exactly as typed;
 * the notional is derived when requests are compared (ADR 0003).
 */
data class Request(
    val refToken: String,
    val chatId: Long,
    val userId: Long,
    val username: String?,
    val shortId: String,
    val side: Side,
    val statedCurrency: String,
    val statedAmount: BigDecimal,
    val pair: CurrencyPair,
    val state: RequestState,
    val createdAt: Instant,
    val expiresAt: Instant,
    /** The sibling link: every row born of one interest carries the same value. Null for a request typed in a chat. */
    val interestToken: String? = null,
)
