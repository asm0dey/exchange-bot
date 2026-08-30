package fxbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.api.message.editMessageReplyMarkup

/** One `/cancel` can touch several messages, and Telegram rate-limits edits. */
private const val FAN_OUT = 10

/**
 * Strips the inline keyboard from any message whose buttons referenced a request
 * that just closed — a stale "Done"/"Cancel" button left on screen invites a
 * confusing second press.
 *
 * Bounded per token: [MessageLogRepository.messagesForToken] returns at most
 * [FAN_OUT] carriers, newest first. Anything past that bound is caught by the
 * on-press rejection path — [LifecycleService] re-derives state from the database
 * on every press, never from whether a button still looks live — so a message this
 * pass doesn't reach is stale but harmless, not broken.
 */
class ButtonService(private val log: MessageLogRepository) {
    suspend fun stripFor(closedTokens: List<String>, chatId: Long, bot: TelegramBot) {
        val targets = closedTokens
            .flatMap { log.messagesForToken(it, FAN_OUT) }
            .filter { it.chatId == chatId }
            .distinct()
        for (target in targets) {
            // No runCatching here: `send()` never throws on a Telegram-side failure (message
            // already deleted, bot kicked, etc. — those come back as a Response the callee
            // doesn't even look at), so catching around it caught nothing. A message this
            // pass doesn't reach or can't edit stays stale but harmless — the on-press
            // rejection path in LifecycleService re-derives state from the database on every
            // press regardless of what a button still looks like.
            editMessageReplyMarkup(target.messageId).send(target.chatId, bot)
        }
    }
}
