package fxbot

import java.math.BigDecimal
import java.time.Instant

enum class RequestState {
    OPEN, DONE, CANCELLED, EXPIRED;

    val isTerminal get() = this != OPEN
}

/**
 * The chat id a request with no chat behind it carries. Telegram never issues 0, and
 * `Matcher` already filters on `chatId ==`, so the two surfaces cannot meet by accident.
 */
const val NO_CHAT_ID = 0L

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

/**
 * Whether somebody spoke to the bot privately. True for every row born of a stated
 * interest — including its showings in chats, which the person never typed in — and for
 * the chatless row itself.
 */
fun Request.spokePrivately(): Boolean = interestToken != null || chatId == NO_CHAT_ID

/**
 * Where this person is answered: their own chat with the bot when they spoke privately,
 * otherwise the chat they typed in. A private chat's id IS the person's user id.
 */
fun Request.answerChatId(): Long = if (spokePrivately()) userId else chatId
