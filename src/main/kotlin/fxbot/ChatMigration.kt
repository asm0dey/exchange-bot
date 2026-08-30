package fxbot

import eu.vendeli.tgbot.annotations.UpdateHandler
import eu.vendeli.tgbot.types.component.MessageKind
import eu.vendeli.tgbot.types.component.MessageUpdate
import eu.vendeli.tgbot.types.component.ProcessedUpdate
import eu.vendeli.tgbot.types.component.UpdateType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * A group upgraded to a supergroup keeps nothing of its old chat id. The id lives
 * in the ref columns and inside each sealed payload, so the repositories reseal
 * their rows; the AAD is the ref token and never changes (ADR 0002).
 */
class ChatMigrationService(
    private val requests: RequestRepository,
    private val settings: ChatSettingsRepository,
    private val log: MessageLogRepository,
    private val db: Database,
) {
    /**
     * All three rewrites are one transaction. Exposed leaves `useNestedTransactions`
     * false by default and this project's `connectExposed` does not set it, so each
     * repository's own `transaction(db)` joins this outer one and defers its commit
     * to it — the migration is all-or-nothing. Without that, a crash between the
     * calls would strand a chat's settings and message record under a chat ref
     * nothing resolves any more, which is the silent split-state this task exists
     * to prevent.
     */
    fun migrate(oldChatId: Long, newChatId: Long): Int = transaction(db) {
        val moved = requests.rewriteChatRef(oldChatId, newChatId)
        settings.rewriteChatRef(oldChatId, newChatId)
        log.rewriteChatRef(oldChatId, newChatId)
        moved
    }
}

/**
 * Telegram sends the migration notice as an ordinary "message" update whose
 * `Message.migrateToChatId` is set — verified against the 9.6.0 sources jar
 * (`eu.vendeli.tgbot.types.msg.Message`, `eu.vendeli.tgbot.utils.common.ProcessUpdate`,
 * where `message != null -> MessageUpdate(...)` is the only path that reaches it;
 * there is no dedicated migration update type). `messageKind` narrows dispatch to
 * exactly this case (`MessageKind.detectKind()` maps `migrateToChatId != null` to
 * `MIGRATE_TO_CHAT`), so this handler doesn't run on every ordinary message; the
 * `?: return` stays as a belt-and-braces guard. Nothing is said back to the chat —
 * the point of this handler is that nobody notices anything happened.
 */
@UpdateHandler([UpdateType.MESSAGE], messageKind = [MessageKind.MIGRATE_TO_CHAT])
suspend fun onChatMigration(update: ProcessedUpdate) {
    val message = (update as? MessageUpdate)?.message ?: return
    val newChatId = message.migrateToChatId ?: return
    Registry.migration.migrate(message.chat.id, newChatId)
}
