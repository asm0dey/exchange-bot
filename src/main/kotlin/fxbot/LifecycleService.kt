package fxbot

sealed interface ActionResult {
    val text: String

    /** [closedTokens] lists every request just closed, so button cleanup knows what to strip. */
    data class Ok(override val text: String, val closedTokens: List<String>) : ActionResult
    data class Denied(override val text: String) : ActionResult
    data class Gone(override val text: String) : ActionResult
}

/**
 * Authorization is decided here, from the acting user id — never from anything a
 * client sent us (callback_data is a UI suggestion, not proof of identity). Cancel
 * and reopen are the owner's alone; either counterparty may confirm a swap happened.
 */
class LifecycleService(private val requests: RequestRepository) {

    fun cancel(chatId: Long, userId: Long, shortId: String): ActionResult {
        val r = requests.byShortId(chatId, shortId)
            ?: return ActionResult.Gone("I can't find a waiting request called $shortId here.")
        return cancelByToken(userId, r.refToken)
    }

    fun cancelByToken(userId: Long, token: String): ActionResult {
        val r = requests.byRefToken(token)
            ?: return ActionResult.Gone("That request is gone.")
        if (r.userId != userId) return ActionResult.Denied("That's not your request.")
        if (r.state != RequestState.OPEN) return ActionResult.Gone("That request is already closed.")
        return if (requests.transition(token, RequestState.OPEN, RequestState.CANCELLED)) {
            ActionResult.Ok("Withdrawn.", listOf(token))
        } else {
            ActionResult.Gone("That request is already closed.")
        }
    }

    /**
     * Holding one valid token must not authorise closing an unrelated second request.
     * Both tokens are published to every member of the chat inside `callback_data`, so a
     * modified client can pair its own token with any other it has seen. Two checks close
     * that: the presser must own one of the two, and the two must be a pair the bot could
     * plausibly have suggested — same chat, opposite sides, different people.
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
            return ActionResult.Denied("Those two requests aren't a pair I can close together.")
        }

        return when (requests.markDone(mine.refToken, theirs?.refToken)) {
            DoneOutcome.BOTH ->
                ActionResult.Ok("Marked done. If that's wrong, /reopen.", listOfNotNull(mine.refToken, theirs?.refToken))
            DoneOutcome.PEER_GONE ->
                ActionResult.Ok("Closed only your own — the other request was already closed.", listOf(mine.refToken))
            DoneOutcome.ALREADY_CLOSED ->
                ActionResult.Gone("That one is already closed.")
        }
    }

    fun doneByShortId(chatId: Long, userId: Long, shortId: String, peerUserId: Long?): ActionResult {
        val mine = requests.byShortId(chatId, shortId)
            ?: return ActionResult.Gone("I can't find a waiting request called $shortId here.")
        if (mine.userId != userId) return ActionResult.Denied("That's not your request.")
        val theirs = peerUserId?.let { peer -> requests.resting(chatId).firstOrNull { it.userId == peer } }
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
        return if (requests.reopen(last.refToken, tifDays)) {
            ActionResult.Ok("Back on the waitlist: ${describe(last)}", emptyList())
        } else {
            ActionResult.Gone("That one is already waiting.")
        }
    }
}
