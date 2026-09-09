package fxbot


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
    private val people: PersonSettingsRepository,
    private val refusals: DoneRefusalRepository,
    private val pending: PendingAnnouncementRepository,
) {
    /**
     * [personal] covers what belongs to the person rather than to a chat: their own size
     * tolerance and their pending announcements. It is true for both private forms and
     * false for the per-chat one, because `/forget` typed in a group still means that
     * group alone.
     *
     * [messageChatId] scopes the MESSAGE cleanup, which is not always the same scope as
     * the requests. The private form erases requests resting under [NO_CHAT_ID],
     * but nothing is ever RECORDED under that sentinel — a private reply that names a
     * counterparty is recorded under the person's real private chat id. Passing that id
     * here is what makes that message actually get redacted; defaulting to [chatId] keeps
     * the group and `all` forms behaving exactly as before.
     */
    fun plan(userId: Long, chatId: Long?, personal: Boolean, messageChatId: Long? = chatId): ForgetPlan {
        val messages = log.messagesForUser(userId, messageChatId)
        val (redact, delete) = messages.partition { log.namesOthers(it.messageId, it.chatId, userId) }
        val removed = requests.deleteFor(userId, chatId)
        // Unconditional, unlike the personal-only rows below: a refusal row is keyed on two
        // request tokens, and the requests these name have just been erased, so the rows are
        // dead whichever shape of forgetting this was.
        refusals.deleteFor(removed)
        if (personal) {
            people.delete(userId)
            pending.deleteFor(userId)
        }
        log.forget(userId, messageChatId)
        return ForgetPlan(removed.size, delete, redact)
    }
}
