package fxbot

import java.time.Clock

data class ForgetPlan(
    val deletedRequests: Int,
    val toDelete: List<TrackedMessage>,
    val toRedact: List<TrackedMessage>,
)

/**
 * Erases what is stored, and works out what can still be cleaned up in the chat.
 * A message that named other people is redacted rather than deleted, so their
 * names and their working buttons survive (ADR 0005).
 */
class ForgetService(
    private val requests: RequestRepository,
    private val log: MessageLogRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun plan(userId: Long, chatId: Long?): ForgetPlan {
        val messages = log.messagesForUser(userId, chatId)
        val (redact, delete) = messages.partition { log.namesOthers(it.messageId, it.chatId, userId) }
        val removed = requests.deleteFor(userId, chatId).size
        log.forget(userId, chatId)
        return ForgetPlan(removed, delete, redact)
    }
}
