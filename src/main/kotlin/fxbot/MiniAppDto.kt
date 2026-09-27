package fxbot

import kotlinx.serialization.Serializable

/** Decimals travel as strings so no figure is rounded by a JSON double on either side. */
@Serializable data class RangeDto(val min: String, val max: String? = null)

@Serializable data class CounterpartyDto(
    val shortId: String, val name: String, val says: Says, val amount: String, val currency: String,
    /** The viewer's own row in the scope this counterparty was found in — what a done names as "mine". */
    val mineToken: String,
    val chatId: Long, val chatTitle: String? = null,
)

@Serializable data class CardDto(
    val shortId: String, val mine: Boolean, val says: Says, val amount: String, val currency: String,
    val other: String, val approxOther: String? = null, val name: String,
    val createdAt: Long, val expiresAt: Long,
    /** Only on the viewer's own card. */
    val token: String? = null,
    val counterparties: List<CounterpartyDto> = emptyList(),
    /** Only on other people's cards. Base currency. */
    val range: RangeDto? = null,
)

@Serializable data class ChatView(
    val chatId: Long, val title: String? = null, val base: String, val quote: String,
    val rate: String? = null, val rateStale: Boolean = false, val cards: List<CardDto>,
)

/** Anonymous by construction: no field here can name, address or authorize anyone. */
@Serializable data class BrowseCard(
    val base: String, val quote: String, val says: Says, val amount: String, val currency: String,
    val other: String, val approxOther: String? = null, val createdAt: Long, val expiresAt: Long,
    val range: RangeDto? = null,
)

@Serializable data class BrowseView(val cards: List<BrowseCard>, val rates: Map<String, String>)

@Serializable data class PendingDto(
    val declarerToken: String, val mineToken: String, val name: String,
    val says: Says, val amount: String, val currency: String, val other: String,
)

@Serializable data class MineDto(
    val token: String, val says: Says, val amount: String, val currency: String, val other: String,
    val approxOther: String? = null, val base: String, val quote: String,
    val chats: List<ChatRef>, val counterparties: List<CounterpartyDto>, val expiresAt: Long,
)

@Serializable data class ChatRef(val id: Long, val title: String? = null)

@Serializable data class MeView(val tolerancePct: Int, val limit: Int, val pending: List<PendingDto>, val mine: List<MineDto>)

@Serializable data class NewRequestBody(val says: Says, val amount: String, val currency: String)
@Serializable data class NewInterestBody(val says: Says, val amount: String, val currency: String, val other: String)
@Serializable data class DoneBody(val mineToken: String, val peerShortId: String)
@Serializable data class AnswerBody(val declarerToken: String, val mineToken: String)
@Serializable data class ToleranceBody(val pct: Int)
@Serializable data class MessageDto(val message: String)

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    /** Refused by a service: the text is the bot's own wording, shown under the form. */
    data class Refused(val message: String) : ApiResult<Nothing>
}
