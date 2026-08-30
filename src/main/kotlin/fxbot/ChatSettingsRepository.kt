package fxbot

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Clock
import javax.sql.DataSource

data class ChatSettings(
    val chatId: Long,
    val pair: CurrencyPair,
    val tolerancePct: Int,
    val tifDays: Int,
)

@Serializable
private data class SettingsPayload(val base: String, val quote: String, val tolerancePct: Int, val tifDays: Int)

private val DEFAULT_PAIR = CurrencyPair("EUR", "RUB")
private const val DEFAULT_TOLERANCE = 20
private const val DEFAULT_TIF_DAYS = 7

/**
 * This row's associated data is the chat ref itself, not a ref token, so a chat
 * migration must decrypt under the old ref and reseal under the new one — the one
 * row in the schema that does, rather than only rewriting a column.
 */
class ChatSettingsRepository(
    ds: DataSource,
    private val crypto: Crypto,
    private val clock: Clock = Clock.systemUTC(),
    // connectExposed(ds) is memoized per DataSource; see RequestRepository's constructor comment.
    private val db: Database = connectExposed(ds),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** No row for this chat means the defaults — `get` never writes one. */
    fun get(chatId: Long): ChatSettings = transaction(db) {
        val chatRef = crypto.ref(chatId.toString())
        val payload = readIn(chatRef)
            ?: return@transaction ChatSettings(chatId, DEFAULT_PAIR, DEFAULT_TOLERANCE, DEFAULT_TIF_DAYS)
        ChatSettings(chatId, CurrencyPair(payload.base, payload.quote), payload.tolerancePct, payload.tifDays)
    }

    fun save(s: ChatSettings): Unit = transaction(db) {
        val chatRef = crypto.ref(s.chatId.toString())
        val body = SettingsPayload(s.pair.base, s.pair.quote, s.tolerancePct, s.tifDays)
        writeIn(chatRef, crypto.seal(json.encodeToString(body), chatRef))
    }

    fun allPairs(): Set<CurrencyPair> = transaction(db) {
        ChatSettingsTable.selectAll().map { row ->
            val p = json.decodeFromString<SettingsPayload>(
                crypto.open(row[ChatSettingsTable.payload], row[ChatSettingsTable.chatRef]),
            )
            CurrencyPair(p.base, p.quote)
        }.toSet()
    }

    /**
     * Reads the row under the old ref, reseals the same payload under the new ref
     * (the AAD here IS the chat ref, unlike every other table), and drops the old
     * row — all inside one transaction. Returns false when there was nothing to
     * migrate.
     */
    fun rewriteChatRef(oldChatId: Long, newChatId: Long): Boolean = transaction(db) {
        val oldRef = crypto.ref(oldChatId.toString())
        val newRef = crypto.ref(newChatId.toString())
        val payload = readIn(oldRef) ?: return@transaction false
        writeIn(newRef, crypto.seal(json.encodeToString(payload), newRef))
        ChatSettingsTable.deleteWhere { ChatSettingsTable.chatRef eq oldRef }
        true
    }

    private fun readIn(chatRef: String): SettingsPayload? =
        ChatSettingsTable.selectAll()
            .where { ChatSettingsTable.chatRef eq chatRef }
            .singleOrNull()
            ?.let { json.decodeFromString<SettingsPayload>(crypto.open(it[ChatSettingsTable.payload], chatRef)) }

    private fun writeIn(chatRef: String, sealed: ByteArray) {
        ChatSettingsTable.upsert {
            it[ChatSettingsTable.chatRef] = chatRef
            it[payload] = sealed
            it[updatedAt] = clock.instant()
        }
    }
}
