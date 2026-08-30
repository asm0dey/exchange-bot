package fxbot

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Clock
import java.time.Instant
import javax.sql.DataSource

data class TrackedMessage(val chatId: Long, val messageId: Long)

@Serializable
private data class MessagePayload(val chatId: Long)

/**
 * What the bot said, and about whom — the record that lets forgetting (Task 12)
 * reach past the database and into the chat, and lets [ButtonService] find every
 * message whose buttons need to go stale when a request closes.
 *
 * `sent_message` is one row per posted message; `sent_message_ref` fans that
 * message out to every ref token (and the person behind it) its buttons named —
 * one suggestion post can carry several counterparties' tokens at once.
 */
class MessageLogRepository(
    ds: DataSource,
    private val crypto: Crypto,
    private val clock: Clock = Clock.systemUTC(),
    // connectExposed(ds) is memoized per DataSource; see RequestRepository's constructor comment.
    private val db: Database = connectExposed(ds),
) {
    private val json = Json { ignoreUnknownKeys = true }

    // Explicit (not implicit) join: neither table declares a foreign key to the
    // other — Flyway's schema doesn't either — so Exposed's FK-inferring
    // `innerJoin(otherTable)` has nothing to infer from. The pair is a composite
    // key (chat_ref, message_id), which the single onColumn/otherColumn form
    // can't express, hence additionalConstraint. Verified against the 1.5.0
    // exposed-core sources (Table.kt): `ColumnSet.innerJoin` is the extension
    // overload that accepts it.
    private val messagesWithRefs = SentMessages.innerJoin(
        SentMessageRefs,
        additionalConstraint = {
            (SentMessages.chatRef eq SentMessageRefs.chatRef) and (SentMessages.messageId eq SentMessageRefs.messageId)
        },
    )

    /** Records one sent message and every ref token (and person) its buttons named. */
    fun record(chatId: Long, messageId: Long, refTokens: List<String>, userIds: List<Long>) {
        require(refTokens.size == userIds.size) { "refTokens and userIds must pair up 1:1" }
        transaction(db) {
            val chatRef = crypto.ref(chatId.toString())
            val aad = "$chatRef:$messageId"
            SentMessages.upsert {
                it[SentMessages.chatRef] = chatRef
                it[SentMessages.messageId] = messageId
                it[sentAt] = clock.instant()
                it[payload] = crypto.seal(json.encodeToString(MessagePayload(chatId)), aad)
            }
            SentMessageRefs.batchInsert(refTokens.indices, shouldReturnGeneratedValues = false) { i ->
                this[SentMessageRefs.chatRef] = chatRef
                this[SentMessageRefs.messageId] = messageId
                this[SentMessageRefs.refToken] = refTokens[i]
                this[SentMessageRefs.userRef] = crypto.ref(userIds[i].toString())
            }
        }
    }

    /** Every message whose buttons named [refToken], newest first, capped at [limit]. */
    fun messagesForToken(refToken: String, limit: Int): List<TrackedMessage> = transaction(db) {
        messagesWithRefs
            .select(SentMessages.chatRef, SentMessages.messageId, SentMessages.payload)
            .where { SentMessageRefs.refToken eq refToken }
            .orderBy(SentMessages.sentAt to SortOrder.DESC, SentMessages.messageId to SortOrder.DESC)
            .limit(limit)
            .map { hydrate(it) }
    }

    /** Every message naming [userId], scoped to [chatId] when given, across every chat otherwise. */
    fun messagesForUser(userId: Long, chatId: Long?): List<TrackedMessage> = transaction(db) {
        val userRef = crypto.ref(userId.toString())
        val predicate = if (chatId == null) {
            SentMessageRefs.userRef eq userRef
        } else {
            (SentMessageRefs.userRef eq userRef) and (SentMessages.chatRef eq crypto.ref(chatId.toString()))
        }
        messagesWithRefs
            .select(SentMessages.chatRef, SentMessages.messageId, SentMessages.payload)
            .where { predicate }
            .withDistinct()
            .map { hydrate(it) }
    }

    /** True when the message also named somebody other than [userId] — so redact, don't delete. */
    fun namesOthers(messageId: Long, chatId: Long, userId: Long): Boolean = transaction(db) {
        val chatRef = crypto.ref(chatId.toString())
        val userRef = crypto.ref(userId.toString())
        !SentMessageRefs.selectAll()
            .where {
                (SentMessageRefs.chatRef eq chatRef) and (SentMessageRefs.messageId eq messageId) and
                    (SentMessageRefs.userRef neq userRef)
            }
            .empty()
    }

    /** Drops [userId]'s own rows — [chatId] scopes to one chat, `null` forgets them everywhere. */
    fun forget(userId: Long, chatId: Long?) {
        transaction(db) {
            val userRef = crypto.ref(userId.toString())
            val predicate = if (chatId == null) {
                SentMessageRefs.userRef eq userRef
            } else {
                (SentMessageRefs.userRef eq userRef) and (SentMessageRefs.chatRef eq crypto.ref(chatId.toString()))
            }
            SentMessageRefs.deleteWhere { predicate }
        }
    }

    /** Drops every row sent before [before]. Returns the number of messages dropped. */
    fun prune(before: Instant): Int = transaction(db) {
        val expired = SentMessages.selectAll()
            .where { SentMessages.sentAt less before }
            .map { it[SentMessages.chatRef] to it[SentMessages.messageId] }
        for ((chatRef, messageId) in expired) {
            SentMessageRefs.deleteWhere {
                (SentMessageRefs.chatRef eq chatRef) and (SentMessageRefs.messageId eq messageId)
            }
        }
        SentMessages.deleteWhere { SentMessages.sentAt less before }
    }

    /**
     * A supergroup migration changes the chat id, which lives both in the ref
     * column and inside each sealed payload, so every `sent_message` row is
     * individually resealed — the same pattern as `RequestRepository.rewriteChatRef`.
     * `sent_message_ref` carries no payload, so its chat_ref moves in one UPDATE.
     */
    fun rewriteChatRef(oldChatId: Long, newChatId: Long): Int = transaction(db) {
        val oldRef = crypto.ref(oldChatId.toString())
        val newRef = crypto.ref(newChatId.toString())
        val messageIds = SentMessages.selectAll()
            .where { SentMessages.chatRef eq oldRef }
            .map { it[SentMessages.messageId] }
        var updated = 0
        for (messageId in messageIds) {
            val resealed = crypto.seal(json.encodeToString(MessagePayload(newChatId)), "$newRef:$messageId")
            updated += SentMessages.update({
                (SentMessages.chatRef eq oldRef) and (SentMessages.messageId eq messageId)
            }) {
                it[SentMessages.chatRef] = newRef
                it[payload] = resealed
            }
        }
        SentMessageRefs.update({ SentMessageRefs.chatRef eq oldRef }) { it[SentMessageRefs.chatRef] = newRef }
        updated
    }

    private fun hydrate(row: ResultRow): TrackedMessage {
        val chatRef = row[SentMessages.chatRef]
        val messageId = row[SentMessages.messageId]
        val p = json.decodeFromString<MessagePayload>(crypto.open(row[SentMessages.payload], "$chatRef:$messageId"))
        return TrackedMessage(chatId = p.chatId, messageId = messageId)
    }
}
