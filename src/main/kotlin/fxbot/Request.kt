package fxbot

import java.math.BigDecimal
import java.time.Instant

enum class RequestState {
    OPEN, DONE, CANCELLED, EXPIRED;

    val isTerminal get() = this != OPEN
}

/**
 * Someone's stated willingness to exchange. Amounts are held exactly as typed;
 * the notional is derived when requests are compared (ADR 0003).
 */
data class Request(
    val rowId: Long,
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
)
