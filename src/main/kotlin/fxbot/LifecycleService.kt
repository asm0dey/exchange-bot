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

    /** Either side of the swap may press Done; a third party may not. */
    fun done(userId: Long, mineToken: String, theirsToken: String?): ActionResult {
        val mine = requests.byRefToken(mineToken)
            ?: return ActionResult.Gone("That request is gone.")
        val theirs = theirsToken?.let { requests.byRefToken(it) }
        val isParticipant = mine.userId == userId || theirs?.userId == userId
        if (!isParticipant) return ActionResult.Denied("Only the two people swapping can mark this done.")

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

    fun reopen(chatId: Long, userId: Long, tifDays: Int = 7): ActionResult {
        val last = requests.mostRecentlyClosed(chatId, userId)
            ?: return ActionResult.Gone("You have nothing closed here to bring back.")
        return if (requests.reopen(last.refToken, tifDays)) {
            ActionResult.Ok("Back on the waitlist: ${describe(last)}", emptyList())
        } else {
            ActionResult.Gone("That one is already waiting.")
        }
    }
}
