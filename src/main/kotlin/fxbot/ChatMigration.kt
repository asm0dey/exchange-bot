package fxbot

import eu.vendeli.tgbot.annotations.UpdateHandler
import eu.vendeli.tgbot.types.component.MessageUpdate
import eu.vendeli.tgbot.types.component.ProcessedUpdate
import eu.vendeli.tgbot.types.component.UpdateType

/**
 * A group upgraded to a supergroup keeps nothing of its old chat id. The id lives
 * in the ref columns and inside each sealed payload, so the repositories reseal
 * their rows; the AAD is the ref token and never changes (ADR 0002).
 */
class ChatMigrationService(
    private val requests: RequestRepository,
    private val settings: ChatSettingsRepository,
    private val log: MessageLogRepository,
) {
    fun migrate(oldChatId: Long, newChatId: Long): Int {
        val moved = requests.rewriteChatRef(oldChatId, newChatId)
        settings.rewriteChatRef(oldChatId, newChatId)
        log.rewriteChatRef(oldChatId, newChatId)
        return moved
    }
}

/**
 * Telegram sends the migration notice as an ordinary "message" update whose
 * `Message.migrateToChatId` is set — verified against the 9.6.0 sources jar
 * (`eu.vendeli.tgbot.types.msg.Message`, `eu.vendeli.tgbot.utils.common.ProcessUpdate`,
 * where `message != null -> MessageUpdate(...)` is the only path that reaches it;
 * there is no dedicated migration update type). Nothing is said back to the chat —
 * the point of this handler is that nobody notices anything happened.
 */
@UpdateHandler([UpdateType.MESSAGE])
suspend fun onChatMigration(update: ProcessedUpdate) {
    val message = (update as? MessageUpdate)?.message ?: return
    val newChatId = message.migrateToChatId ?: return
    Registry.migration.migrate(message.chat.id, newChatId)
}
