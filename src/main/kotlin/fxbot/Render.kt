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

    fun done(mine: String, theirs: String) = "$DONE?a=$mine&b=$theirs"
    fun cancel(token: String) = "$CANCEL?t=$token"
    fun reopen(token: String) = "$REOPEN?t=$token"
}

data class Button(val label: String, val data: String)

fun escapeHtml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** `@name` when there is one, otherwise the only mention Telegram allows. */
fun mention(username: String?, userId: Long, displayName: String): String =
    if (username != null) "@${escapeHtml(username)}"
    else """<a href="tg://user?id=$userId">${escapeHtml(displayName)}</a>"""

/** Always the author's own words — never the converted figure. */
fun describe(r: Request): String {
    val verb = if (verbFor(r.side, r.statedCurrency, r.pair) == Verb.SELL) "sell" else "buy"
    return "$verb ${formatAmount(r.statedAmount)} ${r.statedCurrency}"
}

fun renderSuggestions(found: List<Counterparty>, status: RateStatus): String {
    val lines = StringBuilder()
    if (found.isEmpty()) {
        lines.append("No one matches yet — you're on the waitlist.")
    } else {
        lines.append(if (found.size == 1) "1 person matches:" else "${found.size} people match:")
        for (c in found) {
            val who = mention(c.request.username, c.request.userId, c.request.username ?: "this person")
            lines.append("\n• ").append(who).append(" — ").append(describe(c.request))
            val n = c.notional
            if (n != null && c.request.statedCurrency != c.request.pair.base) {
                lines.append(" (≈").append(formatNotional(n)).append(' ').append(c.request.pair.base).append(')')
            }
        }
        lines.append("\nAgree the rate between yourselves, then press Done.")
    }
    when (status) {
        is RateStatus.Stale -> lines.append("\n(rate from ").append(DAY_MONTH.format(status.fetchedAt)).append(", may be out of date)")
        RateStatus.Unavailable -> lines.append("\n(I can't check rates right now, so I can only match amounts in the same currency)")
        is RateStatus.Fresh -> Unit
    }
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
        val who = mention(r.username, r.userId, r.username ?: "this person")
        text.append("\n• ").append(r.shortId).append(' ').append(who).append(" — ").append(describe(r))
        if (r.userId == viewerId) text.append("  (yours)")
    }
    val hidden = requests.size - shown.size
    if (hidden > 0) text.append("\n+").append(hidden).append(" more")
    return text.toString()
}
