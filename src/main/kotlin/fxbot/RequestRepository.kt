package fxbot

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import javax.sql.DataSource
import kotlin.concurrent.withLock

@Serializable
private data class Payload(
    val chatId: Long,          // stored so a MAC-keyset rotation can re-derive chat_ref
    val userId: Long,
    val username: String?,
    val side: String,
    val statedCurrency: String,
    val statedAmount: String,   // string, never a JSON number — see Global Constraints
    val base: String,
    val quote: String,
)

/** Base32-ish labels, shortest first: a…z, then a0…z9. */
private val SHORT_IDS: List<String> =
    ('a'..'z').map { it.toString() } + ('a'..'z').flatMap { c -> ('0'..'9').map { "$c$it" } }

enum class DoneOutcome { BOTH, ALREADY_CLOSED, PEER_GONE }

class RequestRepository(
    ds: DataSource,
    private val crypto: Crypto,
    private val clock: Clock = Clock.systemUTC(),
    // connectExposed(ds) is memoized per DataSource, so this default is safe to call
    // from every RequestRepository built on the same pool — it never registers a
    // second Database with Exposed's transaction manager. Exists as a parameter (not
    // just a computed property) so a future composition root that already holds a
    // Database — e.g. Main.kt, connecting once for every repository — can hand it in
    // directly instead of resolving it again through this same memo table.
    private val db: Database = connectExposed(ds),
) {
    private val json = Json { ignoreUnknownKeys = true }

    // Short ids are unique only among a chat's live requests, and H2 has no partial
    // unique index to enforce that. `SELECT ... FOR UPDATE` cannot do it either: it
    // locks the rows it returns, so a chat with nothing resting locks nothing and two
    // concurrent creates both pick "a". This lock is the guard, and it holds because
    // the bot is a single process (ADR 0004).
    // ponytail: one global lock, not per-chat — allocation is microseconds at chat
    // scale. Move to a per-chat lock if a busy deployment ever shows contention, and
    // to a database-level guard if the bot is ever run as more than one instance.
    private val allocationLock = ReentrantLock()

    fun create(
        chatId: Long,
        userId: Long,
        username: String?,
        side: Side,
        statedCurrency: String,
        statedAmount: BigDecimal,
        pair: CurrencyPair,
        tifDays: Int,
    ): Request = allocationLock.withLock {
        transaction(db) {
            val chatRef = crypto.ref(chatId.toString())
            val shortId = allocateShortId(chatRef)
            val refToken = newRefToken()
            val now = clock.instant()
            val expires = now.plusSeconds(tifDays.toLong() * 86_400)
            val payload = Payload(
                chatId, userId, username, side.name, statedCurrency,
                statedAmount.toPlainString(), pair.base, pair.quote,
            )
            Requests.insert {
                it[Requests.refToken] = refToken
                it[Requests.chatRef] = chatRef
                it[Requests.userRef] = crypto.ref(userId.toString())
                it[Requests.shortId] = shortId
                it[Requests.state] = RequestState.OPEN.name
                it[Requests.createdAt] = now
                it[Requests.expiresAt] = expires
                it[Requests.payload] = crypto.seal(json.encodeToString(payload), refToken)
            }
            Request(
                refToken = refToken, chatId = chatId, userId = userId,
                username = username, shortId = shortId, side = side,
                statedCurrency = statedCurrency, statedAmount = statedAmount, pair = pair,
                state = RequestState.OPEN, createdAt = now, expiresAt = expires,
            )
        }
    }

    /** The caller holds [allocationLock] and is already inside its transaction; see its comment for why that is the guard. */
    private fun allocateShortId(chatRef: String): String {
        val taken = Requests.selectAll()
            .where { (Requests.chatRef eq chatRef) and (Requests.state eq RequestState.OPEN.name) }
            .map { it[Requests.shortId] }
            .toSet()
        return SHORT_IDS.firstOrNull { it !in taken }
            ?: error("this chat has more resting requests than there are short ids")
    }

    fun resting(chatId: Long): List<Request> = transaction(db) {
        val chatRef = crypto.ref(chatId.toString())
        Requests.selectAll()
            .where { (Requests.chatRef eq chatRef) and (Requests.state eq RequestState.OPEN.name) }
            .orderBy(Requests.expiresAt to SortOrder.ASC, Requests.rowId to SortOrder.ASC)
            .map { hydrate(it) }
    }

    fun byRefToken(token: String): Request? = transaction(db) {
        Requests.selectAll().where { Requests.refToken eq token }.firstOrNull()?.let { hydrate(it) }
    }

    fun byShortId(chatId: Long, shortId: String): Request? = transaction(db) {
        val chatRef = crypto.ref(chatId.toString())
        Requests.selectAll()
            .where {
                (Requests.chatRef eq chatRef) and (Requests.shortId eq shortId) and
                    (Requests.state eq RequestState.OPEN.name)
            }
            .firstOrNull()?.let { hydrate(it) }
    }

    fun mostRecentlyClosed(chatId: Long, userId: Long): Request? = transaction(db) {
        val chatRef = crypto.ref(chatId.toString())
        val userRef = crypto.ref(userId.toString())
        Requests.selectAll()
            .where {
                (Requests.chatRef eq chatRef) and (Requests.userRef eq userRef) and
                    (Requests.state neq RequestState.OPEN.name)
            }
            .orderBy(Requests.closedAt to SortOrder.DESC, Requests.rowId to SortOrder.DESC)
            .limit(1)
            .firstOrNull()?.let { hydrate(it) }
    }

    /**
     * Guarded by the expected state, so a double press closes exactly once.
     * Stamps `closed_at` so "most recently closed" means what it says.
     */
    fun transition(refToken: String, from: RequestState, to: RequestState): Boolean = transaction(db) {
        Requests.update({ (Requests.refToken eq refToken) and (Requests.state eq from.name) }) {
            it[Requests.state] = to.name
            it[Requests.closedAt] = if (to == RequestState.OPEN) null else clock.instant()
        } == 1
    }

    /** Stamps `closed_at` like every other close, so recency ordering sees expiries. */
    fun expireDue(now: Instant): Int = transaction(db) {
        Requests.update({ (Requests.state eq RequestState.OPEN.name) and (Requests.expiresAt less now) }) {
            it[Requests.state] = RequestState.EXPIRED.name
            it[Requests.closedAt] = now
        }
    }

    /** Returns the ref tokens removed, so message cleanup knows what to strip. */
    fun deleteFor(userId: Long, chatId: Long?): List<String> = transaction(db) {
        val userRef = crypto.ref(userId.toString())
        val chatRef = chatId?.let { crypto.ref(it.toString()) }
        val predicate = if (chatRef == null) Requests.userRef eq userRef
                        else (Requests.userRef eq userRef) and (Requests.chatRef eq chatRef)
        val tokens = Requests.selectAll().where { predicate }.map { it[Requests.refToken] }
        Requests.deleteWhere { predicate }
        tokens
    }

    /**
     * A supergroup migration changes the chat id, which lives both in the ref
     * column and inside the sealed payload, so each row is resealed. The AAD is
     * the ref token and does not change, so the tokens stay valid throughout.
     *
     * The read and every reseal happen inside one transaction, so a row inserted
     * into the old chat mid-migration is not stranded under a chat ref that no
     * longer resolves.
     */
    fun rewriteChatRef(oldChatId: Long, newChatId: Long): Int = transaction(db) {
        val oldRef = crypto.ref(oldChatId.toString())
        val newRef = crypto.ref(newChatId.toString())
        val rows = Requests.selectAll().where { Requests.chatRef eq oldRef }.map { hydrate(it) }
        var updated = 0
        for (r in rows) {
            val resealed = crypto.seal(
                json.encodeToString(
                    Payload(
                        newChatId, r.userId, r.username, r.side.name, r.statedCurrency,
                        r.statedAmount.toPlainString(), r.pair.base, r.pair.quote,
                    )
                ),
                r.refToken,
            )
            updated += Requests.update({ Requests.refToken eq r.refToken }) {
                it[Requests.chatRef] = newRef
                it[Requests.payload] = resealed
            }
        }
        updated
    }

    /**
     * Closes both sides in one Exposed transaction, each guarded by its expected
     * state, so two people pressing Done at the same moment close it exactly once:
     * the `UPDATE ... WHERE state = 'OPEN'` only ever affects a row still resting,
     * the same row-level guard [transition] already relies on.
     */
    fun markDone(mine: String, theirs: String?): DoneOutcome = transaction(db) {
        fun close(token: String): Boolean =
            Requests.update({ (Requests.refToken eq token) and (Requests.state eq RequestState.OPEN.name) }) {
                it[Requests.state] = RequestState.DONE.name
                it[Requests.closedAt] = clock.instant()
            } == 1

        if (!close(mine)) {
            DoneOutcome.ALREADY_CLOSED
        } else {
            val theirsClosed = theirs?.let(::close) ?: true
            if (theirs != null && !theirsClosed) DoneOutcome.PEER_GONE else DoneOutcome.BOTH
        }
    }

    /** Puts a closed request back with a fresh expiry; clears `closed_at`, like every reopen via [transition] does. */
    fun reopen(refToken: String, tifDays: Int): Boolean = transaction(db) {
        val expires = clock.instant().plusSeconds(tifDays.toLong() * 86_400)
        Requests.update({ (Requests.refToken eq refToken) and (Requests.state neq RequestState.OPEN.name) }) {
            it[Requests.state] = RequestState.OPEN.name
            it[Requests.expiresAt] = expires
            it[Requests.closedAt] = null
        } == 1
    }

    /** Every field the domain needs comes out of the sealed payload. */
    private fun hydrate(row: ResultRow): Request {
        val refToken = row[Requests.refToken]
        val p = json.decodeFromString<Payload>(crypto.open(row[Requests.payload], refToken))
        return Request(
            refToken = refToken,
            chatId = p.chatId,
            userId = p.userId,
            username = p.username,
            shortId = row[Requests.shortId],
            side = Side.valueOf(p.side),
            statedCurrency = p.statedCurrency,
            statedAmount = BigDecimal(p.statedAmount),
            pair = CurrencyPair(p.base, p.quote),
            state = RequestState.valueOf(row[Requests.state]),
            createdAt = row[Requests.createdAt],
            expiresAt = row[Requests.expiresAt],
        )
    }
}
