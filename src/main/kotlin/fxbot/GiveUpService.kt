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
     * either side of the pairing without a second lookup. Both are stripped of their
     * `username` (see [Request.copy] at the call site): the recipient of the message this
     * builds has not consented yet, so the identifying value this whole service exists to
     * protect must not ride along on the request itself just because the idiomatic way to
     * render a [Request] elsewhere in this codebase happens to print it.
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

private const val YOUR_REQUEST_GONE = "Your own request isn't waiting anymore, so there's nothing to offer from it."

private const val SELF_PAIRING = "You can't pass your own name to yourself."

private const val NOT_COUNTERPARTIES =
    "Those two aren't counterparties of each other, so there's no name to pass between them."

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
        // A row keyed (token, token) would satisfy bothOffered() on a single press,
        // disclosing the presser to themselves. Nobody else is exposed, but it is a
        // nonsense result, so it is refused before either token is even looked up.
        if (myToken == peerToken) return GiveUpResult.Refused(SELF_PAIRING)
        val mine = requests.byRefToken(myToken) ?: return GiveUpResult.Refused(PEER_GONE)
        if (mine.userId != userId) return GiveUpResult.Refused(NOT_YOURS)
        val theirs = requests.byRefToken(peerToken) ?: return GiveUpResult.Refused(PEER_GONE)
        // A pairing the presser themselves declined gets its own wording — it is not
        // "gone", it is the presser's own earlier choice — and that fact takes priority
        // over the OPEN-state check below: telling a decliner that the PEER'S request has
        // since closed both misstates what happened (their own "no" is the operative fact)
        // and hands them a fresh, unnecessary fact about the peer's current state. This
        // must also run before the symmetric declined() check further down, which would
        // otherwise answer true for either direction and mask which side actually declined.
        if (giveUps.stanceOf(myToken, peerToken) == Stance.DECLINED) return GiveUpResult.Refused(YOU_DECLINED)
        // Each state checked against its own wording: closing is something that happens
        // to ONE side's request, and the message should name whichever side it was.
        if (mine.state != RequestState.OPEN) return GiveUpResult.Refused(YOUR_REQUEST_GONE)
        if (theirs.state != RequestState.OPEN) return GiveUpResult.Refused(PEER_GONE)
        if (giveUps.declined(myToken, peerToken)) return GiveUpResult.Refused(PEER_GONE)
        // The peer must be a counterparty of the presser's, not merely some OPEN request.
        // A ref token is not a secret in practice: `announcementButtons` publishes
        // counterparty tokens in group callback_data, and this codebase's own threat model
        // (see LifecycleService.done) says a modified client can read them. Without this,
        // anyone in any group could harvest tokens and have the bot DM each of those people
        // "someone has offered to pass their name" about a pairing it never made. No name
        // passes without the recipient pressing, so this is not a disclosure — it is an
        // unauthenticated way to make the bot message strangers, which is enough.
        //
        // Structural only — same chat, same pair, opposite sides, two different people. The
        // sizes are deliberately NOT re-checked, for the reason `done` gives: either
        // person's size tolerance may have moved since they were shown each other, and two
        // people who were offered each other must still be able to answer.
        if (theirs.chatId != mine.chatId || theirs.pair != mine.pair ||
            theirs.side == mine.side || theirs.userId == mine.userId
        ) {
            return GiveUpResult.Refused(NOT_COUNTERPARTIES)
        }
        if (!introducible(mine, theirs)) return GiveUpResult.Refused(NO_ROUTE)

        giveUps.record(myToken, peerToken, userId, Stance.OFFERED)
        if (!giveUps.bothOffered(myToken, peerToken)) {
            // Name-stripped: the peer has not consented yet, so nothing that renders
            // `mine` or `theirs` the ordinary way (mentionOf + describe) may print an
            // @username here.
            return GiveUpResult.Asked(
                theirs.userId, peerToken, myToken,
                mine.copy(username = null), theirs.copy(username = null),
            )
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
