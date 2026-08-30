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
            // One message failing to edit (deleted by a user, bot kicked, etc.) must not
            // stop the rest of the fan-out from being cleaned up.
            runCatching { editMessageReplyMarkup(target.messageId).send(target.chatId, bot) }
        }
    }
}
