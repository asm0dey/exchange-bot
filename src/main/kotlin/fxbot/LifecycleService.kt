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
    ) : ActionResult
    data class Denied(override val text: String) : ActionResult
    data class Gone(override val text: String) : ActionResult
}

/** Fixed outcome label for command-surface logging — never the [ActionResult.text] itself,
 *  which can carry a counterparty's name via [mention]. */
internal fun ActionResult.outcomeLabel(): String = when (this) {
    is ActionResult.Ok -> "ok"
    is ActionResult.Denied -> "denied"
    is ActionResult.Gone -> "gone"
}

/**
 * Who a typed `/done` named, as far as the command surface could resolve them. Three
 * cases, not two, because on the no-names side "somebody I can't place" and "somebody who
 * never agreed to pass names" MUST read as one refusal: if they read differently, the
 * command answers, for any handle a stranger cares to type, whether that person is resting
 * anything with the bot at all.
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
 * apart — a structurally impossible pairing, a name nothing here belongs to, and a peer
 * who never agreed to pass names all read exactly like this.
 */
private const val NOT_A_PAIR = "Those two requests aren't a pair I can close together."

/**
 * Authorization is decided here, from the acting user id — never from anything a
 * client sent us (callback_data is a UI suggestion, not proof of identity). Cancel
 * and reopen are the owner's alone; either counterparty may confirm a swap happened.
 */
class LifecycleService(
    private val requests: RequestRepository,
    private val settings: ChatSettingsRepository,
    private val rates: RateService,
    /** Consent, for the one authorization a chat's own visibility cannot stand in for. */
    private val giveUps: NameGiveUpRepository,
) {

    /** Text sent with HTML parse mode — see [mention] — so callers must send it that way. */
    private fun nameOf(r: Request) = mention(r.username, r.userId, r.username ?: "this person")

    /** Each chat's own time in force, and the no-names default for the row with no chat. */
    private fun tifFor(chatId: Long): Int =
        if (chatId == NO_NAMES_CHAT_ID) NO_NAMES_TIF_DAYS else settings.get(chatId).tifDays

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
     * Holding one valid token must not authorise closing an unrelated second request.
     * Both tokens are published to every member of the chat inside `callback_data`, so a
     * modified client can pair its own token with any other it has seen. Two checks close
     * that: the presser must own one of the two, and the two must be a pair the bot could
     * plausibly have suggested — same chat, opposite sides, different people.
     *
     * Those two are the whole of the authorization in a CHAT, where every member can
     * already read every name, a wrongful close is publicly visible, and `/reopen` undoes
     * it. On the no-names side none of that holds, and the structural check passes
     * trivially there — both rows carry `chatId = 0`, and the opposite side is arranged by
     * stating it. So a third check applies to a sentinel-scoped pairing: the two must have
     * actually passed names, read from the give-up table rather than from anything a
     * client sent. Without it a stranger closes somebody's whole interest, in every chat
     * it is shown in, and is told "Marked done: @them and @you" — the bot naming a person
     * who never agreed to be named (ADR 0007).
     *
     * The check belongs HERE, not only in [doneByShortId]: this is what a Done button
     * reaches, and the give-up button the bot hands a presser already carries the peer's
     * sentinel ref token, so rewriting `giveup?a=X&b=Y` into `done?a=X&b=Y` is one word of
     * work. The only legitimate no-names Done button is minted by `discloseTo`, which runs
     * only once `bothOffered` is already true, so nothing legitimate is refused.
     *
     * The refusal is [NOT_A_PAIR] — the same words a structurally impossible pairing gets.
     * A distinct "they never agreed" would itself confirm that the person behind a
     * harvested token rests an interest, which is the disclosure this check prevents.
     *
     * The size tolerance is deliberately NOT re-checked: two people are free to agree a
     * swap the bot would not have introduced them for, and this only records that they did.
     */
    fun done(userId: Long, mineToken: String, theirsToken: String?): ActionResult {
        val a = requests.byRefToken(mineToken)
            ?: return ActionResult.Gone("That request is gone.")
        val b = theirsToken?.let { requests.byRefToken(it) }

        if (a.userId != userId && b?.userId != userId) {
            return ActionResult.Denied("Only the two people swapping can mark this done.")
        }
        // Report outcomes relative to whoever pressed, not to the button's argument order —
        // a naive `mine = a` here is exactly the bug where the presser is told the WRONG
        // request is "already closed" while their own sits untouched.
        val mine = if (a.userId == userId) a else b!!
        val theirs = if (a.userId == userId) b else a

        if (theirs != null && !(
                theirs.chatId == mine.chatId &&
                theirs.side != mine.side &&
                theirs.userId != mine.userId
            )
        ) {
            return ActionResult.Denied(NOT_A_PAIR)
        }
        if (mine.state != RequestState.OPEN) return ActionResult.Gone("That one is already closed.")
        // Both rows are sentinel-scoped by now — the chats matched just above — so this one
        // test settles the whole pairing. Below the state check deliberately: `mine` is
        // always the presser's OWN row, so "already closed" tells them nothing they did not
        // put there themselves, and a second press of a legitimate Done button keeps saying
        // so even after housekeeping has swept the consent rows the close made spent.
        if (theirs != null && mine.chatId == NO_NAMES_CHAT_ID &&
            !giveUps.bothOffered(mine.refToken, theirs.refToken)
        ) {
            return ActionResult.Denied(NOT_A_PAIR)
        }
        // Both interests close together, in one transaction: a swap is one decision, and a
        // half-applied one would leave a person resting against a counterparty who is gone.
        val closed = requests.closeBothWhole(
            mine,
            theirs?.takeIf { it.state == RequestState.OPEN },
            RequestState.DONE,
        )
        if (closed.mine.isEmpty()) return ActionResult.Gone("That one is already closed.")
        if (theirs != null && closed.theirs.isEmpty()) {
            return ActionResult.Ok(
                "Closed only ${nameOf(mine)}'s — ${nameOf(theirs)}'s was already closed.",
                closed.mine,
            )
        }

        // A known-accepted residual (ADR/progress R45) relies on this announcement to make a
        // force-close visible: a member can pair their own token with an uninvolved same-chat
        // opposite-side request and close it out from under its owner. The mitigation is that
        // the closure names BOTH people publicly and the wronged party can /reopen — so the
        // text MUST name both, not just say "done". `theirs` can still be null here (the
        // command path with no counterparty resolved closes only the caller's own request), in
        // which case there is nobody else to name.
        val text = if (theirs != null) {
            "Marked done: ${nameOf(mine)} and ${nameOf(theirs)}. If that's wrong, /reopen."
        } else {
            "Marked done. If that's wrong, /reopen."
        }
        // Worked out from the two closed rows, in the presser's own stated currency. Nothing
        // is offered when there is none left over, or when the two are stated in different
        // currencies with no reference rate to bridge them.
        val offer = theirs?.let { peer ->
            residualOf(mine, peer, rates.status(mine.pair).rate)?.let { left ->
                RestateOffer(mine.refToken, peer.refToken, left, mine.statedCurrency)
            }
        }
        return ActionResult.Ok(text, closed.mine + closed.theirs, offer)
    }

    /**
     * The typed form. Consent on the no-names side is enforced by [done] itself, so that
     * every route in is covered rather than this one alone — see its own comment.
     *
     * What is left here is the part [done] cannot see: a typed name resolves to a PERSON,
     * and this decides which request of theirs, if any, that means. Two of the three
     * outcomes never reach [done] with a peer at all — a name nothing resting here belongs
     * to, and a named person who rests nothing — and both must refuse rather than fall
     * through to closing the caller's own request alone, because that difference would
     * answer, for any handle a stranger cares to type, whether that person is resting
     * anything with the bot.
     *
     * Every refusal is [NOT_A_PAIR], deliberately: a distinct "they never agreed" would
     * itself confirm that the named person rests an interest, which is the disclosure this
     * check exists to prevent.
     */
    fun doneByShortId(chatId: Long, userId: Long, shortId: String, peer: NamedPeer): ActionResult {
        val mine = requests.byShortId(chatId, shortId)
            // shortId is raw user input, never validated — escaped in case this text is
            // ever sent under an HTML parse mode by some future caller.
            ?: return ActionResult.Gone("I can't find a waiting request called ${escapeHtml(shortId)} here.")
        if (mine.userId != userId) return ActionResult.Denied("That's not your request.")
        val noNames = chatId == NO_NAMES_CHAT_ID
        // In a chat an unplaceable name stays what it has always been — nobody was named,
        // and the caller's own request closes alone.
        if (noNames && peer is NamedPeer.Unplaceable) return ActionResult.Denied(NOT_A_PAIR)
        val theirs = (peer as? NamedPeer.Somebody)
            ?.let { named -> requests.resting(chatId).firstOrNull { it.userId == named.userId } }
        if (noNames && peer is NamedPeer.Somebody &&
            (theirs == null || !giveUps.bothOffered(mine.refToken, theirs.refToken))
        ) {
            return ActionResult.Denied(NOT_A_PAIR)
        }
        return done(userId, mine.refToken, theirs?.refToken)
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
