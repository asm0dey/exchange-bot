package fxbot

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Clock
import java.time.Instant
import javax.sql.DataSource

/**
 * One chat still owed an announcement about one interest. It carries the chat REF, not a
 * chat id: this table has no sealed payload to hide an id in, and a ref is a keyed MAC
 * that cannot be reversed. The flush gets the real chat id from the interest's own
 * showing rows, whose payloads do carry it, and pairs the two with
 * [PendingAnnouncementRepository.isFor].
 */
data class PendingAnnouncement(val chatRef: String, val interestToken: String, val createdAt: Instant)

/**
 * What to announce, never how. The text is re-rendered from live state at flush time, so
 * a restart inside the batching window cannot leave a showing resting in a chat that was
 * never told — and cannot replay a composed message whose requests have since closed.
 */
class PendingAnnouncementRepository(
    ds: DataSource,
    private val crypto: Crypto,
    private val clock: Clock = Clock.systemUTC(),
    // connectExposed(ds) is memoized per DataSource; see RequestRepository's constructor comment.
    private val db: Database = connectExposed(ds),
) {
    fun add(chatId: Long, interestToken: String, userId: Long): Unit = transaction(db) {
        PendingAnnouncements.upsert {
            it[chatRef] = crypto.ref(chatId.toString())
            it[PendingAnnouncements.interestToken] = interestToken
            it[userRef] = crypto.ref(userId.toString())
            it[createdAt] = clock.instant()
        }
    }

    fun all(): List<PendingAnnouncement> = transaction(db) {
        PendingAnnouncements.selectAll().map { hydrate(it) }
    }

    fun allFor(userId: Long): List<PendingAnnouncement> = transaction(db) {
        val userRef = crypto.ref(userId.toString())
        PendingAnnouncements.selectAll().where { PendingAnnouncements.userRef eq userRef }.map { hydrate(it) }
    }

    /** Whether [row] belongs to [chatId], without ever reversing the ref. */
    fun isFor(row: PendingAnnouncement, chatId: Long): Boolean = row.chatRef == crypto.ref(chatId.toString())

    fun remove(chatRef: String, interestToken: String): Unit = transaction(db) {
        PendingAnnouncements.deleteWhere {
            (PendingAnnouncements.chatRef eq chatRef) and (PendingAnnouncements.interestToken eq interestToken)
        }
    }

    fun deleteFor(userId: Long): Unit = transaction(db) {
        val userRef = crypto.ref(userId.toString())
        PendingAnnouncements.deleteWhere { PendingAnnouncements.userRef eq userRef }
    }

    /**
     * A supergroup upgrade changes the chat id, so the ref this row is keyed on stops
     * resolving. Without this rewrite an upgrade inside the batching window orphans the
     * row and that chat is never told — the showing keeps resting and matching, so it
     * degrades quietly, which is exactly why it needs pinning down.
     *
     * Re-keyed rather than updated in place: `chat_ref` is half the primary key, and the
     * new chat could already owe an announcement about the same interest. The `userRef`
     * and `createdAt` columns are carried across verbatim — the person and the age of the
     * row are not what changed.
     */
    fun rewriteChatRef(oldChatId: Long, newChatId: Long): Int = transaction(db) {
        val oldRef = crypto.ref(oldChatId.toString())
        val newRef = crypto.ref(newChatId.toString())
        val rows = PendingAnnouncements.selectAll()
            .where { PendingAnnouncements.chatRef eq oldRef }
            .map {
                Triple(
                    it[PendingAnnouncements.interestToken],
                    it[PendingAnnouncements.userRef],
                    it[PendingAnnouncements.createdAt],
                )
            }
        PendingAnnouncements.deleteWhere { PendingAnnouncements.chatRef eq oldRef }
        for ((token, owner, at) in rows) {
            PendingAnnouncements.upsert {
                it[chatRef] = newRef
                it[interestToken] = token
                it[userRef] = owner
                it[createdAt] = at
            }
        }
        rows.size
    }

    /** An hour-late "someone just stated this" is noise, and the showing works silently regardless. */
    fun dropOlderThan(cutoff: Instant): Int = transaction(db) {
        PendingAnnouncements.deleteWhere { createdAt less cutoff }
    }

    /** An announcement whose showings have all closed has nothing left to say. */
    fun dropClosed(): Int = transaction(db) {
        val live = Requests.selectAll()
            .where { Requests.state eq RequestState.OPEN.name }
            .mapNotNull { it[Requests.interestToken] }
            .toSet()
        val dead = PendingAnnouncements.selectAll()
            .map { it[PendingAnnouncements.chatRef] to it[PendingAnnouncements.interestToken] }
            .filter { (_, token) -> token !in live }
        for ((chatRef, token) in dead) {
            PendingAnnouncements.deleteWhere {
                (PendingAnnouncements.chatRef eq chatRef) and (PendingAnnouncements.interestToken eq token)
            }
        }
        dead.size
    }

    private fun hydrate(row: ResultRow) = PendingAnnouncement(
        chatRef = row[PendingAnnouncements.chatRef],
        interestToken = row[PendingAnnouncements.interestToken],
        createdAt = row[PendingAnnouncements.createdAt],
    )
}
