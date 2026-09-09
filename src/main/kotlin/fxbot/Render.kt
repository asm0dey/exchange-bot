package fxbot

import java.math.BigDecimal
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).withZone(ZoneOffset.UTC)

/** Callback payloads, in the framework's `name?param=value` form. */
object Cb {
    const val DONE = "done"
    const val CANCEL = "cancel"
    const val REOPEN = "reopen"
    const val GIVE_UP = "giveup"
    const val DECLINE = "decline"
    const val RESTATE = "restate"

    fun done(mine: String, theirs: String) = "$DONE?a=$mine&b=$theirs"
    fun cancel(token: String) = "$CANCEL?t=$token"
    fun reopen(token: String) = "$REOPEN?t=$token"
    fun giveUp(mine: String, theirs: String) = "$GIVE_UP?a=$mine&b=$theirs"
    fun decline(mine: String, theirs: String) = "$DECLINE?a=$mine&b=$theirs"
    fun restate(mine: String, theirs: String) = "$RESTATE?a=$mine&b=$theirs"
}

data class Button(val label: String, val data: String)

fun escapeHtml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** `@name` when there is one, otherwise the only mention Telegram allows. */
fun mention(username: String?, userId: Long, displayName: String): String =
    if (username != null) "@${escapeHtml(username)}"
    else """<a href="tg://user?id=$userId">${escapeHtml(displayName)}</a>"""

/** How a request's author is named everywhere: the handle, or a link with the stand-in label. */
internal fun mentionOf(r: Request): String = mention(r.username, r.userId, r.username ?: "this person")

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

fun renderSuggestions(found: List<Counterparty>, status: RateStatus): String {
    val lines = StringBuilder()
    if (found.isEmpty()) {
        lines.append("No one matches yet — yours is resting here.")
    } else {
        lines.append(if (found.size == 1) "1 person matches:" else "${found.size} people match:")
        for (c in found) {
            lines.append("\n• ").append(mentionOf(c.request)).append(" — ").append(describe(c.request))
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

fun suggestionButtons(subject: Request, found: List<Counterparty>): List<Button> =
    found.map { c ->
        Button("✅ Done with ${c.request.username ?: "them"}", Cb.done(subject.refToken, c.request.refToken))
    } + Button("✖️ Cancel my request", Cb.cancel(subject.refToken))

fun renderStatus(requests: List<Request>, viewerId: Long, limit: Int = 20): String {
    if (requests.isEmpty()) return "Nothing waiting in this chat right now."
    val shown = requests.take(limit)
    val text = StringBuilder("Waiting in this chat:")
    for (r in shown) {
        text.append("\n• ").append(r.shortId).append(' ').append(mentionOf(r)).append(" — ").append(describe(r))
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
fun renderAnnouncement(person: String, shown: List<ShownInterest>, status: RateStatus): String {
    val text = StringBuilder("I'm showing this on $person's behalf:")
    for (s in shown) {
        text.append("\n• ").append(s.request.shortId).append(' ').append(describe(s.request))
        for (c in s.found) {
            text.append("\n   ↳ ").append(mentionOf(c.request)).append(" — ").append(describe(c.request))
        }
    }
    if (shown.any { it.found.isNotEmpty() }) text.append('\n').append(AGREE_LINE)
    text.append(rateTrailer(status))
    return text.toString()
}

fun announcementButtons(shown: List<ShownInterest>): List<Button> =
    shown.flatMap { s ->
        s.found.map { c -> Button("✅ Done with ${c.request.username ?: "them"}", Cb.done(s.request.refToken, c.request.refToken)) } +
            Button("✖️ Cancel ${s.request.shortId}", Cb.cancel(s.request.refToken))
    }

/**
 * The bot-side ping: side and stated amount, nothing else. No handle, no name, no user
 * id, no chat, no hint of which chats are shared (ADR 0007).
 */
fun renderAppeared(mine: List<ShownInterest>): String {
    val text = StringBuilder("Someone matches an interest you have with me:")
    for (s in mine) {
        text.append("\n• your ").append(describe(s.request)).append(':')
        for (c in s.found) text.append("\n   ↳ someone wants to ").append(describe(c.request))
    }
    text.append("\nI haven't told them who you are. Offer to pass your name and I'll ask them the same.")
    return text.toString()
}

/**
 * The two choices a pairing offers, in one place: every message that puts a
 * pairing in front of somebody offers exactly this pair, with exactly these words.
 */
internal fun nameGiveUpButtons(mine: Request, theirs: Request): List<Button> = listOf(
    Button("🤝 Pass my name (${describe(theirs)})", Cb.giveUp(mine.refToken, theirs.refToken)),
    Button("🚫 Not this one", Cb.decline(mine.refToken, theirs.refToken)),
)

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

fun appearedButtons(mine: List<ShownInterest>): List<Button> =
    mine.flatMap { s -> s.found.flatMap { c -> nameGiveUpButtons(s.request, c.request) } }

/** The immediate private reply: what was found, and where this is about to be shown. */
fun renderStated(r: InterestResult.Stated): String {
    val text = StringBuilder("Noted: ${describe(r.interest)} (${r.interest.shortId}).")
    if (r.found.isEmpty()) {
        text.append("\nNobody matches yet — you're waiting.")
    } else {
        text.append(
            if (r.found.size == 1) "\n1 person matches, no names either way:"
            else "\n${r.found.size} people match, no names either way:",
        )
        for (c in r.found) text.append("\n• someone wants to ").append(describe(c.request))
        text.append("\nOffer to pass your name and I'll ask them the same.")
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
 * The pairings this statement found, each offered on the same two terms every other
 * message offers them on, plus one way out of the interest itself.
 */
fun statedButtons(r: InterestResult.Stated): List<Button> =
    r.found.flatMap { c -> nameGiveUpButtons(r.interest, c.request) } +
        Button("✖️ Cancel ${r.interest.shortId}", Cb.cancel(r.interest.refToken))

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
