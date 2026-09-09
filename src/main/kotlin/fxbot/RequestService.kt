package fxbot

/**
 * All parsing, validation and matching for posting a request, kept out of
 * Telegram entirely so it is testable without a bot. `Commands.kt` only
 * extracts arguments from the update and sends what this returns.
 */
sealed interface PostResult {
    data class Posted(
        val request: Request,
        val found: List<Counterparty>,
        val status: RateStatus,
        /**
         * Counterparties who came in privately and must be told there. Somebody who typed
         * in this chat is told by the message this post produces, in the chat they typed in;
         * somebody whose showing this matched never typed here and may have it muted.
         */
        val appeared: List<CounterpartyAppeared>,
    ) : PostResult

    data class Rejected(val reason: String) : PostResult
}

class RequestService(
    private val requests: RequestRepository,
    private val settings: ChatSettingsRepository,
    private val rates: RateService,
) {
    suspend fun post(
        chatId: Long,
        userId: Long,
        username: String?,
        verb: Verb,
        rawAmount: String,
        rawCurrency: String,
    ): PostResult {
        val chat = settings.get(chatId)
        val amount = parseAmount(rawAmount)
            ?: return PostResult.Rejected("I couldn't read \"$rawAmount\" as an amount. Try: /sell 1000 EUR")
        val currency = parseCurrency(rawCurrency)
            ?: return PostResult.Rejected("\"$rawCurrency\" isn't a currency code I know. Try: /sell 1000 EUR")
        if (!chat.pair.contains(currency)) {
            return PostResult.Rejected("This chat exchanges ${chat.pair}, so I can't do $currency here.")
        }

        val side = sideFor(verb, currency, chat.pair)
        val request = requests.create(chatId, userId, username, side, currency, amount, chat.pair, chat.tifDays)
        val status = rates.status(chat.pair)
        val found = findCounterparties(request, requests.resting(chatId), status.rate, chat.tolerancePct)
        return PostResult.Posted(
            request, found, status,
            found.filter { it.request.spokePrivately() }
                .map { CounterpartyAppeared(it.request.userId, it.request.refToken) },
        )
    }
}
