package fxbot

/**
 * Whether a person is in a chat, asked at fan-out time and never written down. The
 * Telegram layer answers with `getChatMember`; a test answers with a lambda.
 *
 * An implementation that THROWS is read as "not a member": [InterestService] catches per
 * chat, so a bot removed from a group, or one transient network error, costs that chat's
 * showing and nothing else. It must not cost the whole statement — by the time fan-out
 * runs the interest is already resting, and an exception out of `state` would leave a
 * half-built interest behind while telling the person it failed, so their retry burns
 * another slot against the cap. A dropped showing is recoverable (restate, or the chat
 * picks the interest up on a later statement); a resting interest nobody was told about
 * is not.
 */
fun interface MembershipProbe {
    suspend fun isMember(chatId: Long, userId: Long): Boolean
}

/**
 * The cap on interests one person may have resting in the bot. It exists to
 * bound fan-out, so requests typed in a chat are not counted against it. The sixth is
 * REFUSED rather than the oldest being dropped: silently cancelling something a person
 * deliberately stated is the one outcome they cannot undo by knowing the rule.
 */
const val MAX_RESTING_INTERESTS = 5

/** An interest worked with no chat behind it has no chat to set a time in force, so it uses the default. */
const val NO_CHAT_TIF_DAYS = 7

/**
 * The canonical pair: the two codes sorted, so `EUR/RUB` whichever way
 * round it was stated. `Matcher` compares pairs by equality, and this is what puts the
 * two orientations of one pair in the same space.
 */
fun canonicalPair(a: String, b: String): CurrencyPair =
    if (a <= b) CurrencyPair(a, b) else CurrencyPair(b, a)

/** Someone who has just gained a counterparty and should be told, by their own request. */
data class CounterpartyAppeared(val userId: Long, val refToken: String)

/** One interest and the chats where it still rests. Chats whose showing has lapsed are simply absent. */
data class InterestStanding(val interest: Request, val chatIds: List<Long>)

sealed interface InterestResult {
    data class Stated(
        val interest: Request,
        val showings: List<Request>,
        val found: List<Counterparty>,
        val status: RateStatus,
        val appeared: List<CounterpartyAppeared>,
    ) : InterestResult

    data class Rejected(val reason: String) : InterestResult
}

/**
 * Everything about stating an interest to the bot privately, kept out of Telegram so it
 * is testable without a bot. The Telegram layer only extracts arguments and sends what
 * this returns.
 */
class InterestService(
    private val requests: RequestRepository,
    private val chats: ChatSettingsRepository,
    private val people: PersonSettingsRepository,
    private val rates: RateService,
    private val rateClient: RateClient,
    private val giveUps: NameGiveUpRepository,
    private val pending: PendingAnnouncementRepository,
    private val membership: MembershipProbe,
) {
    /**
     * [rawAmountCurrency] is the currency of the amount — what a `/sell` hands over and
     * what a `/buy` receives. [rawOtherCurrency] is the other leg, the one after `for`.
     *
     * Everything created here rests immediately; only the announcement waits for the
     * batch, so a counterparty is never missed because a message had not gone out yet.
     */
    suspend fun state(
        userId: Long,
        username: String?,
        verb: Verb,
        rawAmount: String,
        rawAmountCurrency: String,
        rawOtherCurrency: String,
    ): InterestResult {
        val amount = parseAmount(rawAmount)
            ?: return InterestResult.Rejected("I couldn't read \"$rawAmount\" as an amount. Try: /sell 10 EUR for RUB")
        val mine = parseCurrency(rawAmountCurrency)
            ?: return InterestResult.Rejected("\"$rawAmountCurrency\" isn't a currency code I know. Try: /sell 10 EUR for RUB")
        val other = parseCurrency(rawOtherCurrency)
            ?: return InterestResult.Rejected("\"$rawOtherCurrency\" isn't a currency code I know. Try: /sell 10 EUR for RUB")
        if (mine == other) return InterestResult.Rejected("That needs two different currencies, like /sell 10 EUR for RUB")

        if (requests.countOpenInterests(userId) >= MAX_RESTING_INTERESTS) {
            return InterestResult.Rejected(
                "You already have $MAX_RESTING_INTERESTS interests waiting with me, which is as many as I'll hold. " +
                    "Use /cancel to withdraw one and I'll take this instead.",
            )
        }

        val pair = canonicalPair(mine, other)
        if (!canBePriced(pair)) {
            return InterestResult.Rejected(
                "I can't get a rate for $pair, so I couldn't compare amounts across the two.",
            )
        }

        val interestToken = newRefToken()
        val interest = requests.create(
            NO_CHAT_ID, userId, username, sideFor(verb, mine, pair), mine, amount, pair,
            NO_CHAT_TIF_DAYS, interestToken,
        )
        val showings = fanOutChats(userId, pair).map { chat ->
            // The chat's own orientation, so the side reads correctly to everyone there.
            requests.create(
                chat.chatId, userId, username, sideFor(verb, mine, chat.pair), mine, amount, chat.pair,
                chat.tifDays, interestToken,
            ).also { pending.add(chat.chatId, interestToken, userId) }
        }

        val found = counterparties(interest)
        return InterestResult.Stated(
            interest = interest,
            showings = showings,
            found = found,
            status = rates.status(pair),
            // Symmetric: each side's leftover was already judged against its own number,
            // so everyone this interest found has just gained it in return.
            appeared = found.map { CounterpartyAppeared(it.request.userId, it.request.refToken) },
        )
    }

    /**
     * The counterparties resting in the bot for [subject]. Each side is judged at
     * its own tolerance, and a pairing is hidden while the two can already see each other
     * by name, or while one of them has declined. Nothing about the suppression is stored:
     * it is re-evaluated here every time.
     */
    fun counterparties(subject: Request): List<Counterparty> = findCounterparties(
        subject = subject,
        resting = requests.resting(NO_CHAT_ID),
        rate = rates.status(subject.pair).rate,
        tolerancePct = people.get(subject.userId).tolerancePct,
        peerTolerancePct = { people.get(it.userId).tolerancePct },
        suppressed = { a, b -> giveUps.declined(a.refToken, b.refToken) || alreadyPairedInAChat(a, b) },
    )

    /** Each interest once, with the chats where a showing still rests — where someone can still find you. */
    fun standings(userId: Long): List<InterestStanding> =
        requests.resting(NO_CHAT_ID)
            .filter { it.userId == userId }
            .map { interest -> InterestStanding(interest, liveShowings(interest).map { it.chatId }) }

    /**
     * The rate cache first, and only for a pair no chat already uses, one live fetch.
     * A feed that answers WITHOUT the pair is a refusal — that pair can never be priced.
     * A feed that cannot be reached is not: `RateClient` returns null for both, and
     * refusing a legitimate pair during an outage is the harder failure to explain.
     */
    private suspend fun canBePriced(pair: CurrencyPair): Boolean {
        // [pair] is canonical, so canonicalising a chat's pair is the orientation-blind test.
        val known = chats.allPairs().any { canonicalPair(it.base, it.quote) == pair }
        if (known) return true
        if (rates.status(pair).rate != null) return true
        val fetched = rateClient.fetch(pair.base) ?: return true
        return fetched[pair.quote] != null
    }

    /** The chats a statement on [pair] — which is canonical — should be shown in. */
    private suspend fun fanOutChats(userId: Long, pair: CurrencyPair): List<ChatSettings> =
        chats.allChats()
            .filter { it.fanOut }
            .filter { canonicalPair(it.pair.base, it.pair.quote) == pair }
            .filter { isMember(it.chatId, userId) }

    /** See [MembershipProbe]: a probe that fails answers "no" for that one chat. */
    private suspend fun isMember(chatId: Long, userId: Long): Boolean =
        try {
            membership.isMember(chatId, userId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Our own caller giving up, not the probe failing. Never swallowed.
            throw e
        } catch (e: Exception) {
            false
        }

    /**
     * Whether a live showing already pairs these two people in some chat — in which case
     * the anonymous route adds nothing, because they can see each other by name there.
     * Co-presence is not enough: the two showings must actually be counterparties at that
     * chat's own tolerance, or a pairing that chat would never have suggested would
     * silently block the one this side would.
     */
    private fun alreadyPairedInAChat(a: Request, b: Request): Boolean {
        val mine = liveShowings(a)
        if (mine.isEmpty()) return false
        val theirs = liveShowings(b).associateBy { it.chatId }
        return mine.any { showing ->
            val peer = theirs[showing.chatId] ?: return@any false
            // The showings' own pair, not `chat.pair`: an admin who repairs a chat while
            // these rest leaves the two disagreeing, and a rate for the wrong pair would
            // convert the sizes wrongly and flip the decision. `findCounterparties` has
            // already established the two showings agree on a pair. The TOLERANCE is the
            // chat's current one on purpose — that is the number the chat matches at now.
            val chat = chats.get(showing.chatId)
            findCounterparties(showing, listOf(peer), rates.status(showing.pair).rate, chat.tolerancePct).isNotEmpty()
        }
    }

    /** The rows of [interest] still resting in a chat — its bot-side row is not one of them. */
    private fun liveShowings(interest: Request): List<Request> =
        interest.interestToken
            ?.let { requests.siblings(it) }
            .orEmpty()
            .filter { it.state == RequestState.OPEN && it.chatId != NO_CHAT_ID }
}
