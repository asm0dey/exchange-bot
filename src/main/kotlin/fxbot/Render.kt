package fxbot

import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).withZone(ZoneOffset.UTC)

/** Callback payloads, in the framework's `name?param=value` form. */
object Cb {
    const val DONE = "done"
    const val CANCEL = "cancel"
    const val REOPEN = "reopen"
    const val RESTATE = "restate"
    const val CONFIRM = "yes"
    const val REFUSE = "no"

    /**
     * A done offered to whoever reads the message. `a` is the request the message is
     * about; `b` names the counterparty by USER ID, never by their ref token.
     *
     * A ref token is a bearer capability — owning one is what proves a press is authorized
     * — and callback data is delivered to the client, so a token in `b` handed the reader
     * the other person's capability. A user id hands over nothing: [mention] already
     * renders a handle-less person as a literal `tg://user?id=` link in the very same
     * message, so the id is already in front of the same reader.
     */
    fun done(mine: String, peerUserId: Long) = "$DONE?a=$mine&b=$peerUserId"

    /**
     * True when [data] is a done offered against [userId] — the one payload slot in any
     * builder here that is not a ref token, and so the one [ButtonService] cannot find by
     * token when the person behind it stops resting anything.
     */
    fun doneNames(data: String, userId: Long): Boolean =
        data.startsWith("$DONE?a=") && data.endsWith("&b=$userId")

    fun cancel(token: String) = "$CANCEL?t=$token"
    fun reopen(token: String) = "$REOPEN?t=$token"
    fun restate(mine: String, theirs: String) = "$RESTATE?a=$mine&b=$theirs"

    /**
     * Answering a done. `a` is the DECLARER's request and `b` the request of the person
     * being asked — the reverse of [done]'s ownership, and checked as such: a press is
     * honoured only when the presser owns `b`.
     *
     * BOTH slots stay ref tokens, deliberately, and must not follow [done] to a user id.
     * `b` is the proof of ownership itself. And `a` by user id would be worse than the
     * token it replaced: a forged Yes today needs the declarer's 22 random characters,
     * which only reach a presser the bot actually paired them with, whereas a user id is
     * public and could name anyone at all.
     */
    fun confirm(declarer: String, mine: String) = "$CONFIRM?a=$declarer&b=$mine"
    fun refuse(declarer: String, mine: String) = "$REFUSE?a=$declarer&b=$mine"
}

data class Button(val label: String, val data: String)

fun escapeHtml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** What Telegram knows about somebody right now, asked at render time and never stored. */
data class Handle(val username: String?, val displayName: String)

/** Looks somebody up live. The Telegram layer answers with `getChat`; a test answers with a map. */
fun interface NameLookup {
    suspend fun handleFor(userId: Long): Handle?
}

/** `@name` when there is one, otherwise the only mention Telegram allows. */
fun mention(username: String?, userId: Long, displayName: String): String =
    if (username != null) "@${escapeHtml(username)}"
    else """<a href="tg://user?id=$userId">${escapeHtml(displayName)}</a>"""

/**
 * The display names for the people a message is about to name who carry no stored handle.
 * Empty is always a valid answer: a person nothing could be looked up for keeps the label
 * they have always had, and their `tg://user?id=` link still works.
 */
@JvmInline
value class NameBook(private val byUserId: Map<Long, String>) {
    fun label(r: Request): String = byUserId[r.userId] ?: "this person"

    companion object {
        val EMPTY = NameBook(emptyMap())
    }
}

/**
 * One lookup per person who needs one, and none at all for anybody whose handle is already
 * sealed into their request — the stored username is the source, and this is the fallback
 * for the one thing it cannot supply.
 */
suspend fun nameBookFor(requests: List<Request>, names: NameLookup): NameBook {
    val need = requests.filter { it.username == null }.map { it.userId }.distinct()
    if (need.isEmpty()) return NameBook.EMPTY
    return NameBook(need.mapNotNull { id -> names.handleFor(id)?.let { id to it.displayName } }.toMap())
}

/** How a request's author is named everywhere: the handle, or a link over their display name. */
internal fun mentionOf(r: Request, book: NameBook = NameBook.EMPTY): String =
    mention(r.username, r.userId, r.username ?: book.label(r))

/** The same person on a button, where Telegram allows no markup at all. */
internal fun plainName(r: Request, book: NameBook = NameBook.EMPTY): String = r.username ?: book.label(r)

/** Said once, so every message that carries it cannot drift apart. */
internal const val AGREE_LINE = "Agree the rate between yourselves, then press Done."

/**
 * The rate caveat, in one place: every message that compares two sizes admits the same
 * way when the number behind the comparison is old or missing.
 */
private fun rateTrailer(status: RateStatus): String = when (status) {
    is RateStatus.Stale -> "\n(rate from ${DAY_MONTH.format(status.fetchedAt)}, may be out of date)"
    RateStatus.Unavailable -> "\n(I can't check rates right now, so I can only match amounts in the same currency)"
    is RateStatus.Fresh -> ""
}

/** Always the author's own words — never the converted figure. */
fun describe(r: Request): String {
    val verb = if (verbFor(r.side, r.statedCurrency, r.pair) == Verb.SELL) "sell" else "buy"
    return "$verb ${formatAmount(r.statedAmount)} ${r.statedCurrency}"
}

fun renderSuggestions(found: List<Counterparty>, status: RateStatus, book: NameBook = NameBook.EMPTY): String {
    val lines = StringBuilder()
    if (found.isEmpty()) {
        lines.append("No one matches yet — yours is resting here.")
    } else {
        lines.append(if (found.size == 1) "1 person matches:" else "${found.size} people match:")
        for (c in found) {
            lines.append("\n• ").append(mentionOf(c.request, book)).append(" — ").append(describe(c.request))
            val n = c.notional
            if (n != null && c.request.statedCurrency != c.request.pair.base) {
                lines.append(" (≈").append(formatNotional(n)).append(' ').append(c.request.pair.base).append(')')
            }
        }
        lines.append('\n').append(AGREE_LINE)
    }
    lines.append(rateTrailer(status))
    return lines.toString()
}

fun suggestionButtons(subject: Request, found: List<Counterparty>, book: NameBook = NameBook.EMPTY): List<Button> =
    found.map { c ->
        Button("✅ Done with ${plainName(c.request, book)}", Cb.done(subject.refToken, c.request.userId))
    } + Button("✖️ Cancel my request", Cb.cancel(subject.refToken))

/** One button per person the declarer could have meant; pressing one is an ordinary done. */
fun chooseButtons(r: ActionResult.Choose, book: NameBook = NameBook.EMPTY): List<Button> =
    r.candidates.map { c ->
        Button("✅ Done with ${plainName(c, book)}", Cb.done(r.mineToken, c.userId))
    }

fun renderStatus(requests: List<Request>, viewerId: Long, limit: Int = 20, book: NameBook = NameBook.EMPTY): String {
    if (requests.isEmpty()) return "Nothing waiting in this chat right now."
    val shown = requests.take(limit)
    val text = StringBuilder("Waiting in this chat:")
    for (r in shown) {
        text.append("\n• ").append(r.shortId).append(' ').append(mentionOf(r, book)).append(" — ").append(describe(r))
        if (r.userId == viewerId) text.append("  (yours)")
    }
    val hidden = requests.size - shown.size
    if (hidden > 0) text.append("\n+").append(hidden).append(" more")
    return text.toString()
}

/** One showing (or one bot-side request) as it is about to be announced, with what it found. */
data class ShownInterest(val request: Request, val found: List<Counterparty>)

/**
 * A message nobody typed has to explain itself, so it says whose interest it is showing.
 * [person] is already a `mention(...)`, so send this with HTML parse mode.
 */
fun renderAnnouncement(person: String, shown: List<ShownInterest>, status: RateStatus, book: NameBook = NameBook.EMPTY): String {
    val text = StringBuilder("I'm showing this on $person's behalf:")
    for (s in shown) {
        text.append("\n• ").append(s.request.shortId).append(' ').append(describe(s.request))
        for (c in s.found) {
            text.append("\n   ↳ ").append(mentionOf(c.request, book)).append(" — ").append(describe(c.request))
        }
    }
    if (shown.any { it.found.isNotEmpty() }) text.append('\n').append(AGREE_LINE)
    text.append(rateTrailer(status))
    return text.toString()
}

fun announcementButtons(shown: List<ShownInterest>, book: NameBook = NameBook.EMPTY): List<Button> =
    shown.flatMap { s ->
        s.found.map { c -> Button("✅ Done with ${plainName(c.request, book)}", Cb.done(s.request.refToken, c.request.userId)) } +
            Button("✖️ Cancel ${s.request.shortId}", Cb.cancel(s.request.refToken))
    }

/**
 * What a just-closed done or cancel offers: undo, and — when the presser was left holding
 * more than the swap took — restating what is left, in one press. Said once, so the
 * command path and the button path cannot drift apart.
 *
 * Only for a result that closed something: [ActionResult.Ok.touchedTokens] must not be empty,
 * and a reopen's result never comes here (a resting request has nothing to undo).
 */
internal fun decisionButtons(result: ActionResult.Ok): List<Button> =
    listOf(Button("↩️ Reopen", Cb.reopen(result.touchedTokens.first()))) +
        listOfNotNull(
            result.restate?.let {
                Button(
                    "➕ State the rest (${formatAmount(it.amount)} ${it.currency})",
                    Cb.restate(it.myToken, it.peerToken),
                )
            },
        )

/** The two answers a done allows, in one place so every ask offers exactly these words. */
fun askButtons(r: ActionResult.Asked): List<Button> = listOf(
    Button("✅ Yes, we did", Cb.confirm(r.myToken, r.peerToken)),
    Button("✖️ No, we didn't", Cb.refuse(r.myToken, r.peerToken)),
)

/** What the other side of a confirmed done is offered: undo, and whatever it left them holding. */
internal fun noticeButtons(n: Notice): List<Button> =
    listOf(Button("↩️ Reopen", Cb.reopen(n.reopenToken))) +
        listOfNotNull(
            n.restate?.let {
                Button(
                    "➕ State the rest (${formatAmount(it.amount)} ${it.currency})",
                    Cb.restate(it.myToken, it.peerToken),
                )
            },
        )

/** The immediate private reply: who matches, and where this is about to be shown. */
fun renderStated(r: InterestResult.Stated, book: NameBook = NameBook.EMPTY): String {
    val text = StringBuilder("Noted: ${describe(r.interest)} (${r.interest.shortId}).")
    val found = r.shown.flatMap { it.found }
    if (found.isEmpty()) {
        text.append("\nNobody matches yet — you're waiting.")
    } else {
        text.append(if (found.size == 1) "\n1 person matches:" else "\n${found.size} people match:")
        for (c in found) {
            text.append("\n• ").append(mentionOf(c.request, book)).append(" — ").append(describe(c.request))
        }
        text.append('\n').append(AGREE_LINE)
    }
    text.append(
        when (r.showings.size) {
            0 -> "\nI'm not showing this in any group — either we share none that swap this pair, " +
                "or their admins turned that off."
            1 -> "\nI'll show this in 1 group shortly."
            else -> "\nI'll show this in ${r.showings.size} groups shortly."
        },
    )
    return text.toString()
}

/**
 * A done per pairing, each naming the row it was found against, plus one way out of the
 * interest itself. The two tokens on a button are always in the same scope, which is what
 * [LifecycleService.done]'s pairing check requires.
 */
fun statedButtons(r: InterestResult.Stated, book: NameBook = NameBook.EMPTY): List<Button> =
    r.shown.flatMap { s ->
        s.found.map { c ->
            Button("✅ Done with ${plainName(c.request, book)}", Cb.done(s.request.refToken, c.request.userId))
        }
    } + Button("✖️ Cancel ${r.interest.shortId}", Cb.cancel(r.interest.refToken))

/** Somebody you already told the bot about has just gained a counterparty. */
fun renderAppeared(mine: List<ShownInterest>, book: NameBook = NameBook.EMPTY): String {
    val text = StringBuilder("Someone matches an interest you have with me:")
    for (s in mine) {
        text.append("\n• your ").append(describe(s.request)).append(':')
        for (c in s.found) {
            text.append("\n   ↳ ").append(mentionOf(c.request, book)).append(" — ").append(describe(c.request))
        }
    }
    text.append('\n').append(AGREE_LINE)
    return text.toString()
}

fun appearedButtons(mine: List<ShownInterest>, book: NameBook = NameBook.EMPTY): List<Button> =
    mine.flatMap { s ->
        s.found.map { c ->
            Button("✅ Done with ${plainName(c.request, book)}", Cb.done(s.request.refToken, c.request.userId))
        }
    }

/** Each interest once, and where it still rests. A chat whose showing has lapsed is simply absent. */
fun renderStandings(standings: List<InterestStanding>): String {
    if (standings.isEmpty()) return "You have nothing waiting with me right now."
    val text = StringBuilder("Waiting with me:")
    for (s in standings) {
        text.append("\n• ").append(s.interest.shortId).append(' ').append(describe(s.interest))
        text.append(
            when (s.chatIds.size) {
                0 -> " — waiting with me"
                1 -> " — waiting with me and in 1 group"
                else -> " — waiting with me and in ${s.chatIds.size} groups"
            },
        )
    }
    return text.toString()
}
