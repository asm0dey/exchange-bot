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

/** One person's own size tolerance, used only for no-names working. It never overrides a chat's. */
data class PersonSettings(val userId: Long, val tolerancePct: Int)

@Serializable
private data class PersonPayload(
    val userId: Long,          // stored so a MAC-keyset rotation can re-derive user_ref
    val tolerancePct: Int,
)

const val DEFAULT_PERSON_TOLERANCE = 20

/**
 * Sealed like `chat_settings`, and for the same reason; the associated data here is the
 * `user_ref` itself, not a ref token. Keyed by `user_ref` so forgetting deletes it with
 * the predicate the schema already uses everywhere else.
 *
 * [get] deliberately does NOT persist the default, unlike [ChatSettingsRepository.get]:
 * that write exists there only so the daily rate refresh can enumerate a chat that never
 * ran an admin command, and nothing enumerates person rows. Writing one for everybody who
 * gets looked at would store a row about somebody who never asked for one.
 */
class PersonSettingsRepository(
    ds: DataSource,
    private val crypto: Crypto,
    private val clock: Clock = Clock.systemUTC(),
    // connectExposed(ds) is memoized per DataSource; see RequestRepository's constructor comment.
    private val db: Database = connectExposed(ds),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun get(userId: Long): PersonSettings = transaction(db) {
        val userRef = crypto.ref(userId.toString())
        val payload = PersonSettingsTable.selectAll()
            .where { PersonSettingsTable.userRef eq userRef }
            .singleOrNull()
            ?.let { json.decodeFromString<PersonPayload>(crypto.open(it[PersonSettingsTable.payload], userRef)) }
        PersonSettings(userId, payload?.tolerancePct ?: DEFAULT_PERSON_TOLERANCE)
    }

    fun save(s: PersonSettings): Unit = transaction(db) {
        val userRef = crypto.ref(s.userId.toString())
        val sealed = crypto.seal(json.encodeToString(PersonPayload(s.userId, s.tolerancePct)), userRef)
        PersonSettingsTable.upsert {
            it[PersonSettingsTable.userRef] = userRef
            it[payload] = sealed
            it[updatedAt] = clock.instant()
        }
    }

    /** Bounded 1–100 like the chat command, and rejected without writing anything. */
    fun setTolerance(userId: Long, raw: String): String {
        val pct = parseTolerancePct(raw)
        if (pct == null) return TOLERANCE_HELP
        save(PersonSettings(userId, pct))
        return toleranceSetReply(pct)
    }

    fun delete(userId: Long): Unit = transaction(db) {
        val userRef = crypto.ref(userId.toString())
        PersonSettingsTable.deleteWhere { PersonSettingsTable.userRef eq userRef }
    }
}
