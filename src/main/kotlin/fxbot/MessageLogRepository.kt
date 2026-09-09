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

/** What one message said and offered. Both are nullable/empty for rows written before V3. */
data class LoggedMessage(
    val chatId: Long,
    val messageId: Long,
    val text: String?,
    val buttons: List<Button>,
    val refTokens: List<String>,
)

@Serializable
private data class StoredButton(val label: String, val data: String)

@Serializable
private data class MessagePayload(
    val chatId: Long,
    // Added in V3, inside the sealed payload rather than a new column. Nullable so a row
    // written before V3 falls back to the strip-only path instead of being rewritten to
    // an empty message.
    val text: String? = null,
    val buttons: List<StoredButton> = emptyList(),
)

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

    /**
     * Records one sent message, every ref token (and person) it names, and — since V3 —
     * what it actually said and offered, so closing ONE of the requests a batched message
     * carries can rewrite that message instead of stripping the whole keyboard.
     *
     * Callers MUST pass every ref token their [buttons] name, not just the subject of the
     * message: [ButtonService.refreshFor] decides which buttons survive a close by looking
     * for [refTokens] inside each button's callback data, so a token named by a button but
     * missing from [refTokens] leaves that button live after its request has closed — the
     * exact stale-button press this record exists to prevent, and it fails silently.
     */
    fun record(
        chatId: Long,
        messageId: Long,
        refTokens: List<String>,
        userIds: List<Long>,
        text: String? = null,
        buttons: List<Button> = emptyList(),
    ) {
        require(refTokens.size == userIds.size) { "refTokens and userIds must pair up 1:1" }
        transaction(db) {
            val chatRef = crypto.ref(chatId.toString())
            val aad = "$chatRef:$messageId"
            val body = MessagePayload(chatId, text, buttons.map { StoredButton(it.label, it.data) })
            SentMessages.upsert {
                it[SentMessages.chatRef] = chatRef
                it[SentMessages.messageId] = messageId
                it[sentAt] = clock.instant()
                it[payload] = crypto.seal(json.encodeToString(body), aad)
            }
            // Re-recording a message replaces what it names, rather than accumulating
            // duplicate refs alongside the old ones.
            SentMessageRefs.deleteWhere {
                (SentMessageRefs.chatRef eq chatRef) and (SentMessageRefs.messageId eq messageId)
            }
            SentMessageRefs.batchInsert(refTokens.indices, shouldReturnGeneratedValues = false) { i ->
                this[SentMessageRefs.chatRef] = chatRef
                this[SentMessageRefs.messageId] = messageId
                this[SentMessageRefs.refToken] = refTokens[i]
                this[SentMessageRefs.userRef] = crypto.ref(userIds[i].toString())
            }
        }
    }

    /** Everything needed to rewrite one message: its stored text, its keyboard, and what it names. */
    fun logged(chatId: Long, messageId: Long): LoggedMessage? = transaction(db) {
        val chatRef = crypto.ref(chatId.toString())
        val row = SentMessages.selectAll()
            .where { (SentMessages.chatRef eq chatRef) and (SentMessages.messageId eq messageId) }
            .singleOrNull() ?: return@transaction null
        val p = json.decodeFromString<MessagePayload>(crypto.open(row[SentMessages.payload], "$chatRef:$messageId"))
        val tokens = SentMessageRefs.selectAll()
            .where { (SentMessageRefs.chatRef eq chatRef) and (SentMessageRefs.messageId eq messageId) }
            .map { it[SentMessageRefs.refToken] }
            .distinct()
        LoggedMessage(p.chatId, messageId, p.text, p.buttons.map { Button(it.label, it.data) }, tokens)
    }

    /**
     * Takes one button off a message's stored keyboard and reseals the payload, leaving the
     * message's text and every ref token it names exactly as they were. Answers the buttons
     * that survive, or null when this message never offered [data] — so a caller can edit
     * the message on screen only when the record actually changed.
     *
     * Narrower than [record] on purpose: a re-record rewrites `sent_message_ref` too, and it
     * would need the user ids back, which this row cannot give (they are stored hashed). A
     * withdrawn button changes what the message OFFERS and nothing about whom it names.
     */
    fun dropButton(chatId: Long, messageId: Long, data: String): List<Button>? = transaction(db) {
        val chatRef = crypto.ref(chatId.toString())
        val aad = "$chatRef:$messageId"
        val row = SentMessages.selectAll()
            .where { (SentMessages.chatRef eq chatRef) and (SentMessages.messageId eq messageId) }
            .singleOrNull() ?: return@transaction null
        val p = json.decodeFromString<MessagePayload>(crypto.open(row[SentMessages.payload], aad))
        if (p.buttons.none { it.data == data }) return@transaction null
        val keep = p.buttons.filterNot { it.data == data }
        SentMessages.update({ (SentMessages.chatRef eq chatRef) and (SentMessages.messageId eq messageId) }) {
            it[payload] = crypto.seal(json.encodeToString(p.copy(buttons = keep)), aad)
        }
        keep.map { Button(it.label, it.data) }
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

    /**
     * True when some message logged against [refToken] offered a button whose callback data
     * is exactly [data] — the record that the bot really put that button in front of that
     * request's owner. Answers [AskLookup] for [LifecycleService].
     *
     * Unbounded on purpose, unlike [messagesForToken]: a cap would silently start refusing
     * an honest answer once enough newer messages had named the same token. The scan is
     * bounded in practice by the token's own lifetime — a request rests for its chat's time
     * in force, and [prune] drops everything older than 90 days.
     *
     * Button data is sealed inside each message's payload, so this cannot be a WHERE clause;
     * it opens the same rows [logged] does, which is why it lives here rather than being
     * assembled from two public calls by a caller that has no key.
     */
    fun offered(refToken: String, data: String): Boolean = transaction(db) {
        messagesWithRefs
            .select(SentMessages.chatRef, SentMessages.messageId, SentMessages.payload)
            .where { SentMessageRefs.refToken eq refToken }
            .withDistinct()
            .any { row ->
                val chatRef = row[SentMessages.chatRef]
                val messageId = row[SentMessages.messageId]
                val aad = "$chatRef:$messageId"
                val p = json.decodeFromString<MessagePayload>(crypto.open(row[SentMessages.payload], aad))
                p.buttons.any { it.data == data }
            }
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
            val old = SentMessages.selectAll()
                .where { (SentMessages.chatRef eq oldRef) and (SentMessages.messageId eq messageId) }
                .single()
            val decoded = json.decodeFromString<MessagePayload>(
                crypto.open(old[SentMessages.payload], "$oldRef:$messageId"),
            )
            val resealed = crypto.seal(
                json.encodeToString(decoded.copy(chatId = newChatId)),
                "$newRef:$messageId",
            )
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
