package fxbot

/** What Telegram knows about somebody right now. Never stored (ADR 0007). */
data class Handle(val username: String?, val displayName: String)

/** Looks somebody up live. The Telegram layer answers with `getChat`; a test answers with a map. */
fun interface NameLookup {
    suspend fun handleFor(userId: Long): Handle?
}

/** One person as they are handed to the other: their own request, and how to reach them. */
data class Party(val userId: Long, val refToken: String, val handle: String)

sealed interface GiveUpResult {
    /**
     * The presser's consent is recorded; the other side must now be asked. Carries both
     * requests — [mine] the presser's, [theirs] the peer's own — so a caller can describe
     * either side of the pairing without a second lookup.
     */
    data class Asked(
        val peerUserId: Long,
        val peerRefToken: String,
        val myRefToken: String,
        val mine: Request,
        val theirs: Request,
    ) : GiveUpResult

    /** Both consented: hand each side the other. */
    data class Disclosed(val a: Party, val b: Party) : GiveUpResult

    /** Something was written and there is nothing to disclose — a decline. */
    data class Recorded(val text: String) : GiveUpResult

    /** Nothing was written. The text never names anybody. */
    data class Refused(val text: String) : GiveUpResult
}

private const val NO_ROUTE =
    "I can't introduce you two: neither of you has an @username, and a name alone isn't something " +
        "the other person could act on."

private const val PEER_GONE = "That one is no longer waiting, so there's nobody left to introduce you to."

private const val NOT_YOURS = "That isn't your interest."

private const val YOU_DECLINED = "You already said no to this one, so I won't offer it to you again."

/**
 * Identities pass only by mutual give-up (ADR 0007). Consent is persisted, never carried
 * in a button: the presser is re-derived from `callback_query.from.id` by the caller, and
 * agreement is read from [NameGiveUpRepository], so a hand-crafted payload claiming the
 * other side agreed achieves nothing.
 *
 * Before EITHER side is offered anything, at least one of the two must have an
 * `@username`. A `tg://user?id=` link is reliably actionable only between people who
 * share a chat, which these two by definition do not — so a give-up without a handle
 * would hand over two names neither person can act on. Checking it first fails before
 * anyone consents rather than after both did.
 */
class GiveUpService(
    private val requests: RequestRepository,
    private val giveUps: NameGiveUpRepository,
    private val names: NameLookup,
) {
    /** At least one @username between the two. */
    suspend fun introducible(a: Request, b: Request): Boolean =
        names.handleFor(a.userId)?.username != null || names.handleFor(b.userId)?.username != null

    suspend fun offer(userId: Long, myToken: String, peerToken: String): GiveUpResult {
        val mine = requests.byRefToken(myToken) ?: return GiveUpResult.Refused(PEER_GONE)
        if (mine.userId != userId) return GiveUpResult.Refused(NOT_YOURS)
        val theirs = requests.byRefToken(peerToken) ?: return GiveUpResult.Refused(PEER_GONE)
        if (mine.state != RequestState.OPEN || theirs.state != RequestState.OPEN) {
            return GiveUpResult.Refused(PEER_GONE)
        }
        // A pairing the presser themselves declined gets its own wording — it is not
        // "gone", it is the presser's own earlier choice. This must be checked before the
        // symmetric declined() check below, which would otherwise answer true for either
        // direction and mask which side actually declined.
        if (giveUps.stanceOf(myToken, peerToken) == Stance.DECLINED) return GiveUpResult.Refused(YOU_DECLINED)
        if (giveUps.declined(myToken, peerToken)) return GiveUpResult.Refused(PEER_GONE)
        if (!introducible(mine, theirs)) return GiveUpResult.Refused(NO_ROUTE)

        giveUps.record(myToken, peerToken, userId, Stance.OFFERED)
        if (!giveUps.bothOffered(myToken, peerToken)) {
            return GiveUpResult.Asked(theirs.userId, peerToken, myToken, mine, theirs)
        }
        // Looked up again, right now: a give-up happens days after the interest was
        // stated, so what passes is current, and forgetting has nothing extra to erase.
        val mineHandle = names.handleFor(mine.userId) ?: return GiveUpResult.Refused(NO_ROUTE)
        val theirsHandle = names.handleFor(theirs.userId) ?: return GiveUpResult.Refused(NO_ROUTE)
        return GiveUpResult.Disclosed(
            Party(mine.userId, myToken, mention(mineHandle.username, mine.userId, mineHandle.displayName)),
            Party(theirs.userId, peerToken, mention(theirsHandle.username, theirs.userId, theirsHandle.displayName)),
        )
    }

    /**
     * A decline covers this pairing of requests symmetrically — neither side is offered
     * the other again — and dies with either request. It is deliberately not a durable
     * record of two people who don't want each other.
     *
     * Guarded exactly as [offer] is guarded before any row is written: the presser's own
     * request and the peer's request must both exist, and the presser's token must
     * actually be theirs — a hand-crafted payload naming someone else's request as "mine"
     * writes nothing.
     */
    fun decline(userId: Long, myToken: String, peerToken: String): GiveUpResult {
        val mine = requests.byRefToken(myToken) ?: return GiveUpResult.Refused(PEER_GONE)
        if (mine.userId != userId) return GiveUpResult.Refused(NOT_YOURS)
        requests.byRefToken(peerToken) ?: return GiveUpResult.Refused(PEER_GONE)
        giveUps.record(myToken, peerToken, userId, Stance.DECLINED)
        return GiveUpResult.Recorded("Noted — I won't offer you that one again while it's waiting.")
    }
}
