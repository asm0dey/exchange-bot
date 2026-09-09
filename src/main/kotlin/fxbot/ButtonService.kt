package fxbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.api.message.editMessageReplyMarkup
import eu.vendeli.tgbot.api.message.editMessageText
import eu.vendeli.tgbot.types.component.ParseMode

/** One `/cancel` can touch several messages, and Telegram rate-limits edits. */
private const val FAN_OUT = 10

/**
 * Rewrites every message whose buttons named a request whose state just changed: its
 * stored text plus a status line per request no longer resting, with the keyboard rebuilt
 * from live state in the same pass so the rows whose requests are still open keep theirs.
 *
 * All-or-nothing stripping was tolerable when a message carried one request; a batched
 * message carries several, and closing one interest must not kill the buttons of the
 * others. Rewriting also undoes itself: a reopened request is resting again, so its
 * status line disappears and its button comes back, without this pass needing to know
 * which direction the change went. A message recorded before its text was stored has
 * nothing to rebuild from, so it falls back to the old strip.
 *
 * Bounded per token: [MessageLogRepository.messagesForToken] returns at most [FAN_OUT]
 * carriers, newest first. An edit that does not land leaves a message stale but harmless,
 * because [LifecycleService] re-derives state from the database on every press and never
 * trusts what a button looks like.
 */
class ButtonService(
    private val log: MessageLogRepository,
    private val requests: RequestRepository,
) {
    suspend fun refreshFor(touchedTokens: List<String>, bot: TelegramBot) {
        // Not scoped to a chat: one interest is shown in several at once, and every message
        // carrying it has to be told, wherever it was posted.
        val targets = touchedTokens.flatMap { log.messagesForToken(it, FAN_OUT) }.distinct()
        for (target in targets) {
            val logged = log.logged(target.chatId, target.messageId)
            val storedText = logged?.text
            if (logged == null || storedText == null) {
                // No runCatching: send() never throws on a Telegram-side failure (message
                // already deleted, bot kicked, etc. — those come back as a Response the callee
                // doesn't even look at), so catching around it caught nothing.
                editMessageReplyMarkup(target.messageId).send(target.chatId, bot)
                continue
            }
            val live = logged.refTokens.associateWith { requests.byRefToken(it) }
            val statuses = logged.refTokens.mapNotNull { live[it]?.let(::statusLine) }
            // Drop a button whose callback data names any request that is no longer resting
            // (or has been erased). Every Cb builder spells its tokens out verbatim
            // (`cancel?t=X`, `restate?a=X&b=Y`), so a token match needs no structural
            // knowledge of the keyboard. A done's counterparty is the one slot that is not a
            // token — it names the person by user id (see `Cb.done`) — so that one is found
            // by [Cb.doneNames] instead, and a Done offered against somebody who has stopped
            // resting anything still goes.
            val keep = logged.buttons.filter { button ->
                logged.refTokens.none { token ->
                    val r = live[token]
                    r?.state?.isTerminal != false &&
                        (token in button.data || (r != null && Cb.doneNames(button.data, r.userId)))
                }
            }
            val text = (listOf(storedText) + statuses).joinToString("\n")
            // HTML, like every message this bot stores: the text can carry a mention link.
            editMessageText(target.messageId) { text }
                .options { parseMode = ParseMode.HTML }
                .inlineKeyboardMarkup { keep.forEach { b -> b.label callback b.data; br() } }
                .send(target.chatId, bot)
        }
    }

    /**
     * Takes ONE button off whichever message offered it to [refToken]'s owner, and leaves
     * everything else about that message alone — no status lines, no rebuild from live
     * state, because nothing's state changed. The counterpart of [refreshFor] for a press
     * that answered a question instead of closing a request.
     *
     * A No is the only caller. `refuse` closes nothing, so [refreshFor] never runs for it
     * and the ask keeps its live keyboard; Telegram shows no lasting sign that a button was
     * pressed, so a second tap on the SAME ask spends the declarer's second refusal — and
     * two refusals are meant to be two separate asks (see [MAX_REFUSALS]). So the No comes
     * off and the Yes stays: a change of mind must still work.
     *
     * The stored keyboard is rewritten too, not just the screen. [MessageLogRepository.offered]
     * answers from the record, so a No left there stays pressable by a replayed payload, and
     * the next [refreshFor] over this message would put the button back.
     */
    suspend fun withdrawButton(refToken: String, data: String, bot: TelegramBot) {
        for (target in log.messagesForToken(refToken, FAN_OUT)) {
            val keep = log.dropButton(target.chatId, target.messageId, data) ?: continue
            editMessageReplyMarkup(target.messageId)
                .inlineKeyboardMarkup { keep.forEach { b -> b.label callback b.data; br() } }
                .send(target.chatId, bot)
        }
    }

    /** Says how a request closed, in the vocabulary the person already knows. Null while it rests. */
    private fun statusLine(r: Request): String? = when (r.state) {
        RequestState.OPEN -> null
        RequestState.DONE -> "${r.shortId} — done"
        RequestState.CANCELLED -> "${r.shortId} — withdrawn"
        RequestState.EXPIRED -> "${r.shortId} — lapsed"
    }
}
