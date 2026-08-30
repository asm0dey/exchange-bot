package fxbot

class AdminService(
    private val settings: ChatSettingsRepository,
    private val rateClient: RateClient,
) {
    /** ISO 4217 first, then the feed — an admin cannot pick a pair we can never price. */
    suspend fun setPair(chatId: Long, rawBase: String, rawQuote: String): String {
        val base = parseCurrency(rawBase) ?: return "\"$rawBase\" isn't a currency code I know."
        val quote = parseCurrency(rawQuote) ?: return "\"$rawQuote\" isn't a currency code I know."
        if (base == quote) return "A pair needs two different currencies."
        val rates = rateClient.fetch(base)
        if (rates == null || rates[quote] == null) {
            return "I can't get a rate for $base/$quote, so I couldn't compare amounts across the two."
        }
        val current = settings.get(chatId)
        settings.save(current.copy(pair = CurrencyPair(base, quote)))
        return "This chat now swaps $base/$quote. Requests made before now keep their old currencies until they lapse."
    }

    fun setTolerance(chatId: Long, raw: String): String {
        val pct = raw.toIntOrNull()
        if (pct == null || pct !in 1..100) return "Give me a percentage between 1 and 100, like /tolerance 20"
        settings.save(settings.get(chatId).copy(tolerancePct = pct))
        return "Amounts now match when they're within $pct% of each other."
    }

    fun setTif(chatId: Long, raw: String): String {
        val days = raw.toIntOrNull()
        if (days == null || days !in 1..365) return "Give me a number of days between 1 and 365, like /tif 7"
        settings.save(settings.get(chatId).copy(tifDays = days))
        return "Requests now wait $days day(s) before they lapse."
    }
}
