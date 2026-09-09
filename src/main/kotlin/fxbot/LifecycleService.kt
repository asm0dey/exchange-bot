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
    /** The recipient's own closed request: what Reopen names, and whose message this is. */
    val reopenToken: String,
    val recipientUserId: Long,
    /**
     * The OTHER person [text] names, said outright rather than read off the keyboard. The
     * buttons name them only when the swap left something over, so inferring it from them
     * loses the person entirely on an exactly equal swap — and a message naming somebody
     * that is not recorded against them is out of reach of their `/forget` (ADR 0005).
     */
    val otherToken: String,
    val otherUserId: Long,
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

    /**
     * More than one person could be meant, so nobody is asked and nothing closes. The
     * declarer picks, and the pick is an ordinary done.
     */
    data class Choose(
        override val text: String,
        val mineToken: String,
        val candidates: List<Request>,
    ) : ActionResult
}

/** Fixed outcome label for command-surface logging — never the [ActionResult.text] itself,
 *  which can carry a counterparty's name via [mention]. */
internal fun ActionResult.outcomeLabel(): String = when (this) {
    is ActionResult.Ok -> "ok"
    is ActionResult.Denied -> "denied"
    is ActionResult.Gone -> "gone"
    is ActionResult.Asked -> "asked"
    is ActionResult.Choose -> "choose"
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
 * Said once, so the refusals [LifecycleService.done] and [LifecycleService.doneByShortId] must not tell apart cannot drift
 * apart — a structurally impossible pairing and a name nothing here belongs to both read
 * exactly like this.
 */
private const val NOT_A_PAIR = "Those two requests aren't a pair I can close together."

/**
 * How many times one declarer may be told no about one pairing before [LifecycleService.done]
 * stops asking. A first No is usually an honest mix-up — the wrong short id, the wrong
 * person, a misremembered swap. A second is a pattern.
 *
 * It gates ASKS and nothing else. A Yes is not checked against it — see
 * [LifecycleService.refuse] for why.
 */
const val MAX_REFUSALS = 2

private const val REFUSED_TWICE =
    "They've said no to that twice, so I won't ask them again."

private const val NOT_ASKED = "That isn't a question I asked you."

/**
 * Whether the bot ever put this exact button in front of the person whose request
 * [ActionResult.Asked.myToken] names — the record that an ask actually happened, without a table recording
 * an outstanding one (the design spec forbids that). The Telegram layer answers from the
 * message log, where `sendAsk` writes every question against both ref tokens with its
 * exact button list; a test answers with a lambda.
 *
 * CONSEQUENCE, by design: the 90-day message prune deletes logged messages, so it
 * invalidates an ask nobody has answered yet, and the Yes or No that follows is refused as
 * one nobody was asked. `/forget` does not delete the message — it drops only the ref rows
 * carrying that person's own user ref — so it invalidates the ask from ONE side: the
 * person ASKED forgetting takes away the row this lookup reads through, while the
 * DECLARER forgetting leaves the record standing and their erased request is what refuses
 * the press instead. Either way a forgotten question stops being actionable, which is the
 * behaviour we want, but it is a real way for an honest counterparty's Yes to stop
 * working, so it is stated here rather than discovered. ADR 0009 has the full account.
 */
fun interface AskLookup {
    fun wasAsked(myToken: String, payload: String): Boolean
}

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
    /** Proof that the button being pressed was one the bot actually offered this person. */
    private val asks: AskLookup,
) {

    /** Text sent with HTML parse mode — see [mention] — so callers must send it that way. */
    private fun nameOf(r: Request, book: NameBook) = mentionOf(r, book)

    /**
     * The same person where no markup is parsed. A `Denied`/`Gone` text reaches an
     * `answerCallbackQuery` toast and a plain chat reply, neither of which reads HTML, so
     * a handle-less counterparty named with [nameOf] would show as a literal
     * `<a href="tg://user?id=…">Ann</a>` to exactly the people the name lookup exists to
     * serve. [plainName] is what the button labels already use for the same reason.
     *
     * The `@` is kept, unlike [plainName]: a bare `@handle` needs no markup to be useful —
     * Telegram links it on sight in ordinary message text — so dropping it would cost a
     * tappable name to fix a problem only a handle-LESS person ever had. A button label has
     * no such benefit to lose, which is why [plainName] itself is left as it is.
     */
    private fun plainNameOf(r: Request, book: NameBook) =
        r.username?.let { "@$it" } ?: plainName(r, book)

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
     * the person who would have been wronged by a force close is the person now being asked,
     * and [confirm] honours their answer only if this ask was really made.
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
            return ActionResult.Gone("${plainNameOf(theirs, book)}'s request isn't waiting anymore.")
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

    /**
     * The button form: `a` is the request the message was about and `b` names the
     * counterparty's ROW by its short id, so the second request is looked up rather than
     * carried (see [Cb.done] for why a token there was a capability handed to the reader).
     *
     * Resolved in [mine]'s own scope, which is the scope the button was built in — every
     * builder pairs a request with a counterparty found against it, and [pairable] refuses
     * two rows from different chats anyway. [RequestRepository.byShortId] filters on that
     * chat AND on OPEN, so it names one resting row, never a person: a counterparty
     * resting two things here is exactly what a user id could not tell apart, and telling
     * them apart is the whole point of the slot.
     *
     * The refusal for an id that resolves to nothing is [doneByShortId]'s — the shared
     * [NOT_A_PAIR], never a distinct "nothing rests under that id", which would answer for
     * any short id a stranger cares to try whether something rests there.
     *
     * Ownership is [done]'s and is not re-decided here: a group announcement's Done button
     * is in front of both people, so either of them may press it, and [done] is what
     * requires the presser to own one of the two.
     */
    suspend fun doneWithRow(userId: Long, mineToken: String, peerShortId: String): ActionResult {
        val mine = requests.byRefToken(mineToken) ?: return ActionResult.Gone("That request is gone.")
        val theirs = requests.byShortId(mine.chatId, peerShortId)
            ?: return ActionResult.Denied(NOT_A_PAIR)
        return done(userId, mine.refToken, theirs.refToken)
    }

    /** Same chat, opposite sides, two different people — the shape a suggestion always has. */
    private fun pairable(mine: Request, theirs: Request): Boolean =
        theirs.chatId == mine.chatId && theirs.side != mine.side && theirs.userId != mine.userId

    /**
     * The counterparty says yes. Everything is re-derived here: the presser from the caller's
     * user id, both rows from the database, the pairing structurally. A payload claiming a Yes
     * on somebody else's behalf achieves nothing, because [peerToken] must be the presser's own.
     *
     * Owning [peerToken] is necessary and NOT sufficient: the ask itself must have happened.
     * `askButtons` necessarily sends the person being asked the declarer's token in `a`, so
     * without this check somebody holding two opposite-side requests could pair a token they
     * were legitimately given with their second request and close a request whose owner
     * declared nothing at all — on the chatless surface, where every row shares one chat id,
     * against anyone the bot had ever suggested to them. [asks] closes that: the exact
     * payload must appear in the buttons of a message logged against the presser's OWN
     * request, and only `sendAsk` ever writes one.
     */
    suspend fun confirm(userId: Long, declarerToken: String, peerToken: String): ActionResult {
        val theirs = requests.byRefToken(declarerToken) ?: return ActionResult.Gone("That request is gone.")
        val mine = requests.byRefToken(peerToken) ?: return ActionResult.Gone("That request is gone.")
        if (mine.userId != userId) return ActionResult.Denied(NOT_ASKED)
        if (!asks.wasAsked(peerToken, Cb.confirm(declarerToken, peerToken))) {
            return ActionResult.Denied(NOT_ASKED)
        }
        if (!pairable(mine, theirs)) return ActionResult.Denied(NOT_A_PAIR)
        if (mine.state != RequestState.OPEN) return ActionResult.Gone("Your own request is already closed.")
        val book = nameBookFor(listOf(mine, theirs), names)
        if (theirs.state != RequestState.OPEN) {
            return ActionResult.Gone(
                "${plainNameOf(theirs, book)}'s request is already closed, so there's nothing to confirm.",
            )
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
                recipientUserId = theirs.userId,
                otherToken = mine.refToken,
                otherUserId = mine.userId,
                restate = offerFor(theirs, mine),
            ),
        )
    }

    /**
     * The counterparty says no. Nothing closes and the pairing stands — somebody denying a
     * done they did not make must not lose a real counterparty for it. The refusal is
     * counted against the DECLARER, so a second one stops [done] asking on their behalf
     * about this pairing, and nobody else's.
     *
     * Stops the ASKING, not the closing: [confirm] does not consult the count, so a blocked
     * declarer who was legitimately asked in the other direction can still press Yes there.
     * Checking the count in [confirm] would refuse an honest counterparty's Yes once both
     * directions had been asked and refused.
     *
     * The same [asks] guard [confirm] carries applies here, and for the same reason: a No
     * records a refusal against the declarer, and a refusal nobody was asked for is a way
     * to spend somebody else's two asks without ever being their counterparty.
     */
    fun refuse(userId: Long, declarerToken: String, peerToken: String): ActionResult {
        val theirs = requests.byRefToken(declarerToken) ?: return ActionResult.Gone("That request is gone.")
        val mine = requests.byRefToken(peerToken) ?: return ActionResult.Gone("That request is gone.")
        if (mine.userId != userId) return ActionResult.Denied(NOT_ASKED)
        if (!asks.wasAsked(peerToken, Cb.refuse(declarerToken, peerToken))) {
            return ActionResult.Denied(NOT_ASKED)
        }
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
     * The typed form. A done means a swap, and a swap has a counterparty, so there is no
     * longer any route that closes a request without asking somebody: a name nothing here
     * belongs to refuses, and no name at all resolves to whoever the bot would have
     * suggested — one of them asked, several of them offered as a choice.
     *
     * The refusal for an unplaceable name is [NOT_A_PAIR], deliberately: a distinct "nobody
     * by that name rests anything" would answer, for any handle a stranger cares to type,
     * whether that person is resting anything with the bot.
     */
    suspend fun doneByShortId(chatId: Long, userId: Long, shortId: String, peer: NamedPeer): ActionResult {
        val mine = requests.byShortId(chatId, shortId)
            // shortId is raw user input, never validated — escaped in case this text is
            // ever sent under an HTML parse mode by some future caller.
            ?: return ActionResult.Gone("I can't find a waiting request called ${escapeHtml(shortId)} here.")
        if (mine.userId != userId) return ActionResult.Denied("That's not your request.")
        if (peer is NamedPeer.Unplaceable) return ActionResult.Denied(NOT_A_PAIR)
        if (peer is NamedPeer.Somebody) {
            val theirs = requests.resting(chatId).firstOrNull { it.userId == peer.userId }
                ?: return ActionResult.Denied(NOT_A_PAIR)
            return done(userId, mine.refToken, theirs.refToken)
        }
        val candidates = candidatesFor(mine)
        return when (candidates.size) {
            0 -> ActionResult.Gone(
                "I can't see anyone here you could have swapped with. " +
                    "If you just want the request gone, /cancel ${mine.shortId}.",
            )
            1 -> done(userId, mine.refToken, candidates.single().refToken)
            else -> ActionResult.Choose(
                "More than one person here could be the one. Which of them?",
                mine.refToken,
                candidates,
            )
        }
    }

    /**
     * Who the bot would suggest for [r] right now, judged the way that scope judges: a
     * chat's own size tolerance for a showing or a typed request, each person's own for
     * a request with no chat behind it.
     *
     * [InterestService.counterparties] judges scope the same way and must change with this.
     * They are not shared: this service holds no [InterestService], and giving it one to
     * save the duplication would close a dependency cycle between them.
     */
    private fun candidatesFor(r: Request): List<Request> {
        val resting = requests.resting(r.chatId)
        val rate = rates.status(r.pair).rate
        val found = if (r.chatId == NO_CHAT_ID) {
            findCounterparties(
                r, resting, rate, people.get(r.userId).tolerancePct,
                peerTolerancePct = { people.get(it.userId).tolerancePct },
            )
        } else {
            findCounterparties(r, resting, rate, settings.get(r.chatId).tolerancePct)
        }
        return found.map { it.request }
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
