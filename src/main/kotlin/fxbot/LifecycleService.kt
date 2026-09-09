package fxbot

import java.math.BigDecimal

/**
 * What a done left the presser holding, offered back so they can state it again in one
 * press. Never written to the closed row: the residual is a NEW thing the person states,
 * and the amount they originally typed is never rewritten (ADR 0003).
 */
data class RestateOffer(
    val myToken: String,
    val peerToken: String,
    val amount: BigDecimal,
    val currency: String,
)

/**
 * A second person who has to be told about an outcome somebody else's press produced,
 * in the place THEY spoke to the bot. Carries its own reopen token and its own residual,
 * because the two sides of a done are not left holding the same thing.
 */
data class Notice(
    val chatId: Long,
    val text: String,
    val reopenToken: String,
    val restate: RestateOffer?,
)

sealed interface ActionResult {
    val text: String

    /**
     * [touchedTokens] lists every request whose state just changed — closed by a done or
     * a cancel, revived by a reopen — so the messages that carried them can be rewritten
     * from live state. A reopen belongs here as much as a close does: a carrier message
     * left saying "withdrawn" about a request that is resting again is a lie on screen.
     */
    data class Ok(
        override val text: String,
        val touchedTokens: List<String>,
        val restate: RestateOffer? = null,
        /** Somebody else who must hear about this, where they spoke. */
        val notify: Notice? = null,
    ) : ActionResult

    data class Denied(override val text: String) : ActionResult
    data class Gone(override val text: String) : ActionResult

    /**
     * The counterparty has been asked and nothing has closed. [text] is for the person who
     * declared it; [question] is the message the counterparty gets, in [peerChatId], with
     * the two buttons `askButtons` builds from [myToken] and [peerToken].
     */
    data class Asked(
        override val text: String,
        val declarerUserId: Long,
        val peerUserId: Long,
        val peerChatId: Long,
        val question: String,
        val myToken: String,
        val peerToken: String,
    ) : ActionResult
}

/** Fixed outcome label for command-surface logging — never the [ActionResult.text] itself,
 *  which can carry a counterparty's name via [mention]. */
internal fun ActionResult.outcomeLabel(): String = when (this) {
    is ActionResult.Ok -> "ok"
    is ActionResult.Denied -> "denied"
    is ActionResult.Gone -> "gone"
    is ActionResult.Asked -> "asked"
}

/**
 * Who a typed `/done` named, as far as the command surface could resolve them. Three
 * cases, not two, because "the caller named nobody" and "the caller named somebody
 * nothing here belongs to" are different questions, and the second one must be refused
 * rather than quietly treated as the first.
 */
sealed interface NamedPeer {
    /** No reply, no mention: the caller named nobody. */
    data object Nobody : NamedPeer

    /** Somebody was named, and nothing resting in this scope is theirs. */
    data object Unplaceable : NamedPeer

    data class Somebody(val userId: Long) : NamedPeer
}

/**
 * Said once, so the refusals [done] and [doneByShortId] must not tell apart cannot drift
 * apart — a structurally impossible pairing and a name nothing here belongs to both read
 * exactly like this.
 */
private const val NOT_A_PAIR = "Those two requests aren't a pair I can close together."

/**
 * Two refusals end it. A first No is usually an honest mix-up — the wrong short id, the
 * wrong person, a misremembered swap. A second is a pattern.
 */
const val MAX_REFUSALS = 2

private const val REFUSED_TWICE =
    "They've said no to that twice, so I won't ask them again."

private const val NOT_ASKED = "That isn't a question I asked you."

/**
 * Authorization is decided here, from the acting user id — never from anything a
 * client sent us (callback_data is a UI suggestion, not proof of identity). Cancel
 * and reopen are the owner's alone; either counterparty may declare a swap done, and
 * only the OTHER one can turn that declaration into a close.
 */
class LifecycleService(
    private val requests: RequestRepository,
    private val settings: ChatSettingsRepository,
    private val rates: RateService,
    /** One side of the candidate search: a person's own size tolerance. */
    private val people: PersonSettingsRepository,
    /** Two refusals about one pairing, and no third ask. */
    private val refusals: DoneRefusalRepository,
    /** The one thing a stored row cannot supply: a display name for somebody with no handle. */
    private val names: NameLookup,
) {

    /** Text sent with HTML parse mode — see [mention] — so callers must send it that way. */
    private fun nameOf(r: Request, book: NameBook) = mentionOf(r, book)

    /** Each chat's own time in force, and the bot default for the row with no chat. */
    private fun tifFor(chatId: Long): Int =
        if (chatId == NO_CHAT_ID) NO_CHAT_TIF_DAYS else settings.get(chatId).tifDays

    fun cancel(chatId: Long, userId: Long, shortId: String): ActionResult {
        val r = requests.byShortId(chatId, shortId)
            // shortId is raw user input, never validated — escaped in case this text is
            // ever sent under an HTML parse mode by some future caller.
            ?: return ActionResult.Gone("I can't find a waiting request called ${escapeHtml(shortId)} here.")
        return cancelByToken(userId, r.refToken)
    }

    fun cancelByToken(userId: Long, token: String): ActionResult {
        val r = requests.byRefToken(token)
            ?: return ActionResult.Gone("That request is gone.")
        if (r.userId != userId) return ActionResult.Denied("That's not your request.")
        if (r.state != RequestState.OPEN) return ActionResult.Gone("That request is already closed.")
        // Withdrawing one showing withdraws the interest behind it, wherever else it is shown.
        val closed = requests.closeWhole(r, RequestState.CANCELLED)
        return if (closed.isEmpty()) ActionResult.Gone("That request is already closed.")
        else ActionResult.Ok("Withdrawn.", closed)
    }

    /**
     * Resolves the pairing, guards it, and asks. Nothing closes here, ever.
     *
     * Two checks remain from before: the presser must own one of the two requests, and the
     * two must be a pairing the bot could plausibly have suggested — same chat, opposite
     * sides, different people. The third guard this used to carry, a consent lookup for the
     * chatless side, is gone: the confirmation replaces it with something stronger, because
     * the person who would have been wronged by a force close is the person now being asked.
     *
     * The size tolerance is deliberately NOT re-checked: two people are free to agree a swap
     * the bot would not have introduced them for, and this only records that they did.
     */
    suspend fun done(userId: Long, mineToken: String, theirsToken: String): ActionResult {
        val a = requests.byRefToken(mineToken) ?: return ActionResult.Gone("That request is gone.")
        val b = requests.byRefToken(theirsToken) ?: return ActionResult.Gone("That request is gone.")
        if (a.userId != userId && b.userId != userId) {
            return ActionResult.Denied("Only the two people swapping can mark this done.")
        }
        // Reported relative to whoever acted, not to the argument order.
        val mine = if (a.userId == userId) a else b
        val theirs = if (a.userId == userId) b else a
        if (!pairable(mine, theirs)) return ActionResult.Denied(NOT_A_PAIR)
        if (mine.state != RequestState.OPEN) return ActionResult.Gone("That one is already closed.")
        val book = nameBookFor(listOf(mine, theirs), names)
        if (theirs.state != RequestState.OPEN) {
            return ActionResult.Gone("${nameOf(theirs, book)}'s request isn't waiting anymore.")
        }
        if (refusals.count(mine.refToken, theirs.refToken) >= MAX_REFUSALS) {
            return ActionResult.Denied(REFUSED_TWICE)
        }
        return ActionResult.Asked(
            text = "Asked ${nameOf(theirs, book)} to confirm. Nothing's closed yet.",
            declarerUserId = mine.userId,
            peerUserId = theirs.userId,
            peerChatId = theirs.answerChatId(),
            question = "${nameOf(mine, book)} says you two swapped " +
                "${formatAmount(mine.statedAmount)} ${mine.statedCurrency}. Did you?",
            myToken = mine.refToken,
            peerToken = theirs.refToken,
        )
    }

    /** Same chat, opposite sides, two different people — the shape a suggestion always has. */
    private fun pairable(mine: Request, theirs: Request): Boolean =
        theirs.chatId == mine.chatId && theirs.side != mine.side && theirs.userId != mine.userId

    /**
     * The counterparty says yes. Everything is re-derived here: the presser from the caller's
     * user id, both rows from the database, the pairing structurally. A payload claiming a Yes
     * on somebody else's behalf achieves nothing, because [peerToken] must be the presser's own.
     *
     * A known-accepted residual survives here, inherited from the `done` this replaced:
     * nothing records that an ask was ever made, so somebody who owns an opposite-side
     * request in the same chat can pair it with a harvested token and close that request
     * without its owner having declared anything. The mitigations are the ones that always
     * applied — the outcome names BOTH people, and the person closed out gets the [Notice]
     * with its own Reopen. Closing it properly would need the pressed button checked
     * against the recorded message, which is out of scope here.
     */
    suspend fun confirm(userId: Long, declarerToken: String, peerToken: String): ActionResult {
        val theirs = requests.byRefToken(declarerToken) ?: return ActionResult.Gone("That request is gone.")
        val mine = requests.byRefToken(peerToken) ?: return ActionResult.Gone("That request is gone.")
        if (mine.userId != userId) return ActionResult.Denied(NOT_ASKED)
        if (!pairable(mine, theirs)) return ActionResult.Denied(NOT_A_PAIR)
        if (mine.state != RequestState.OPEN) return ActionResult.Gone("Your own request is already closed.")
        val book = nameBookFor(listOf(mine, theirs), names)
        if (theirs.state != RequestState.OPEN) {
            return ActionResult.Gone("${nameOf(theirs, book)}'s request is already closed, so there's nothing to confirm.")
        }
        // Both interests close together, in one transaction: a swap is one decision, and a
        // half-applied one would leave a person resting against a counterparty who is gone.
        val closed = requests.closeBothWhole(mine, theirs, RequestState.DONE)
        if (closed.mine.isEmpty()) return ActionResult.Gone("That one is already closed.")
        val both = "${nameOf(mine, book)} and ${nameOf(theirs, book)}"
        return ActionResult.Ok(
            text = "Marked done: $both. If that's wrong, /reopen.",
            touchedTokens = closed.mine + closed.theirs,
            restate = offerFor(mine, theirs),
            notify = Notice(
                chatId = theirs.answerChatId(),
                text = "${nameOf(mine, book)} confirmed. Marked done: $both. If that's wrong, /reopen.",
                reopenToken = theirs.refToken,
                restate = offerFor(theirs, mine),
            ),
        )
    }

    /**
     * The counterparty says no. Nothing closes and the pairing stands — somebody denying a
     * done they did not make must not lose a real counterparty for it. The refusal is
     * counted against the DECLARER, so a second one blocks them and nobody else.
     */
    fun refuse(userId: Long, declarerToken: String, peerToken: String): ActionResult {
        val theirs = requests.byRefToken(declarerToken) ?: return ActionResult.Gone("That request is gone.")
        val mine = requests.byRefToken(peerToken) ?: return ActionResult.Gone("That request is gone.")
        if (mine.userId != userId) return ActionResult.Denied(NOT_ASKED)
        if (!pairable(mine, theirs)) return ActionResult.Denied(NOT_A_PAIR)
        refusals.record(theirs.refToken, mine.refToken)
        return ActionResult.Ok("Noted — nothing's closed. Your request is still waiting.", emptyList())
    }

    /**
     * What a done leaves [mine] holding, in their own stated currency. Nothing is offered
     * when there is none left over, or when the two are stated in different currencies with
     * no reference rate to bridge them.
     */
    private fun offerFor(mine: Request, theirs: Request): RestateOffer? =
        residualOf(mine, theirs, rates.status(mine.pair).rate)
            ?.let { RestateOffer(mine.refToken, theirs.refToken, it, mine.statedCurrency) }

    /**
     * The typed form. What is left here is the part [done] cannot see: a typed name
     * resolves to a PERSON, and this decides which request of theirs, if any, that means.
     *
     * Every refusal is [NOT_A_PAIR], deliberately: a distinct "nobody by that name rests
     * anything here" would answer, for any handle a stranger cares to type, whether that
     * person is resting anything with the bot.
     */
    suspend fun doneByShortId(chatId: Long, userId: Long, shortId: String, peer: NamedPeer): ActionResult {
        val mine = requests.byShortId(chatId, shortId)
            // shortId is raw user input, never validated — escaped in case this text is
            // ever sent under an HTML parse mode by some future caller.
            ?: return ActionResult.Gone("I can't find a waiting request called ${escapeHtml(shortId)} here.")
        if (mine.userId != userId) return ActionResult.Denied("That's not your request.")
        val noChat = chatId == NO_CHAT_ID
        // In a chat an unplaceable name stays what it has always been — nobody was named,
        // and the caller's own request closes alone.
        if (noChat && peer is NamedPeer.Unplaceable) return ActionResult.Denied(NOT_A_PAIR)
        val theirs = (peer as? NamedPeer.Somebody)
            ?.let { named -> requests.resting(chatId).firstOrNull { it.userId == named.userId } }
            ?: return ActionResult.Denied(NOT_A_PAIR)
        return done(userId, mine.refToken, theirs.refToken)
    }

    /**
     * The command form (`token = null`) has no id to work from and revives whichever of the
     * caller's own requests closed most recently. The button form names a specific request —
     * [token] — and must act on exactly that one, not "whatever closed last": by the time
     * someone presses Undo, they may have closed something else in the meantime.
     */
    fun reopen(chatId: Long, userId: Long, tifDays: Int, token: String? = null): ActionResult {
        val named = token?.let { requests.byRefToken(it) }
        if (named != null && named.userId != userId) return ActionResult.Denied("That's not your request.")
        val last = named ?: requests.mostRecentlyClosed(chatId, userId)
            ?: return ActionResult.Gone("You have nothing closed here to bring back.")
        // A named token can already be resting — a second press of the same Reopen button.
        // `mostRecentlyClosed` never returns one, so this only guards the button form.
        if (last.state == RequestState.OPEN) return ActionResult.Gone("That one is already waiting.")
        // An interest comes back whole, each showing with its own chat's time in force; a
        // request typed in a chat comes back alone, with the time in force of the chat it
        // was typed in.
        val revived = last.interestToken?.let { requests.reopenInterest(it, last.state, ::tifFor) }
            ?: if (requests.reopen(last.refToken, tifDays)) listOf(last.refToken) else emptyList()
        return if (revived.isEmpty()) ActionResult.Gone("That one is already waiting.")
        else ActionResult.Ok("Resting again: ${describe(last)}", revived)
    }
}
