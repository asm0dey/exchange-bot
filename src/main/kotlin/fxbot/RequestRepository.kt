package fxbot

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import javax.sql.DataSource

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

class RequestRepository(
    private val ds: DataSource,
    private val crypto: Crypto,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun create(
        chatId: Long,
        userId: Long,
        username: String?,
        side: Side,
        statedCurrency: String,
        statedAmount: BigDecimal,
        pair: CurrencyPair,
        tifDays: Int,
    ): Request = ds.connection.use { c ->
        c.autoCommit = false
        try {
            val chatRef = crypto.ref(chatId.toString())
            val shortId = allocateShortId(c, chatRef)
            val refToken = newRefToken()
            val now = clock.instant()
            val expires = now.plusSeconds(tifDays.toLong() * 86_400)
            val payload = Payload(
                chatId, userId, username, side.name, statedCurrency,
                statedAmount.toPlainString(), pair.base, pair.quote,
            )
            c.prepareStatement(
                """
                INSERT INTO request (ref_token, chat_ref, user_ref, short_id, state,
                                     created_at, expires_at, payload)
                VALUES (?, ?, ?, ?, 'OPEN', ?, ?, ?)
                """.trimIndent()
            ).use { st ->
                st.setString(1, refToken)
                st.setString(2, chatRef)
                st.setString(3, crypto.ref(userId.toString()))
                st.setString(4, shortId)
                st.setTimestamp(5, Timestamp.from(now))
                st.setTimestamp(6, Timestamp.from(expires))
                st.setBytes(7, crypto.seal(json.encodeToString(payload), refToken))
                st.executeUpdate()
            }
            c.commit()
            Request(
                rowId = 0, refToken = refToken, chatId = chatId, userId = userId,
                username = username, shortId = shortId, side = side,
                statedCurrency = statedCurrency, statedAmount = statedAmount, pair = pair,
                state = RequestState.OPEN, createdAt = now, expiresAt = expires,
            )
        } catch (e: Exception) {
            c.rollback(); throw e
        }
    }

    /**
     * Short ids are unique only among a chat's live requests, so they can stay
     * short. H2 has no partial unique index, so the guard is this allocation
     * running inside the insert transaction.
     */
    private fun allocateShortId(c: Connection, chatRef: String): String {
        val taken = mutableSetOf<String>()
        c.prepareStatement("SELECT short_id FROM request WHERE chat_ref = ? AND state = 'OPEN' FOR UPDATE").use { st ->
            st.setString(1, chatRef)
            st.executeQuery().use { rs -> while (rs.next()) taken += rs.getString(1) }
        }
        return SHORT_IDS.firstOrNull { it !in taken }
            ?: error("this chat has more resting requests than there are short ids")
    }

    fun resting(chatId: Long): List<Request> = query(
        "SELECT * FROM request WHERE chat_ref = ? AND state = 'OPEN' ORDER BY expires_at"
    ) { st -> st.setString(1, crypto.ref(chatId.toString())) }

    fun byRefToken(token: String): Request? =
        queryOne("SELECT * FROM request WHERE ref_token = ?") { st -> st.setString(1, token) }

    fun byShortId(chatId: Long, shortId: String): Request? = queryOne(
        "SELECT * FROM request WHERE chat_ref = ? AND short_id = ? AND state = 'OPEN'"
    ) { st ->
        st.setString(1, crypto.ref(chatId.toString()))
        st.setString(2, shortId)
    }

    fun mostRecentlyClosed(chatId: Long, userId: Long): Request? = queryOne(
        """
        SELECT * FROM request
        WHERE chat_ref = ? AND user_ref = ? AND state <> 'OPEN'
        ORDER BY row_id DESC LIMIT 1
        """.trimIndent()
    ) { st ->
        st.setString(1, crypto.ref(chatId.toString()))
        st.setString(2, crypto.ref(userId.toString()))
    }

    /** Guarded by the expected state, so a double press closes exactly once. */
    fun transition(refToken: String, from: RequestState, to: RequestState): Boolean =
        ds.connection.use { c ->
            c.prepareStatement("UPDATE request SET state = ? WHERE ref_token = ? AND state = ?").use { st ->
                st.setString(1, to.name)
                st.setString(2, refToken)
                st.setString(3, from.name)
                st.executeUpdate() == 1
            }
        }

    fun expireDue(now: Instant): Int = ds.connection.use { c ->
        c.prepareStatement("UPDATE request SET state = 'EXPIRED' WHERE state = 'OPEN' AND expires_at < ?").use { st ->
            st.setTimestamp(1, Timestamp.from(now))
            st.executeUpdate()
        }
    }

    /** Returns the ref tokens removed, so message cleanup knows what to strip. */
    fun deleteFor(userId: Long, chatId: Long?): List<String> = ds.connection.use { c ->
        val userRef = crypto.ref(userId.toString())
        val chatRef = chatId?.let { crypto.ref(it.toString()) }
        val where = if (chatRef == null) "user_ref = ?" else "user_ref = ? AND chat_ref = ?"
        val tokens = mutableListOf<String>()
        c.prepareStatement("SELECT ref_token FROM request WHERE $where").use { st ->
            st.setString(1, userRef)
            chatRef?.let { st.setString(2, it) }
            st.executeQuery().use { rs -> while (rs.next()) tokens += rs.getString(1) }
        }
        c.prepareStatement("DELETE FROM request WHERE $where").use { st ->
            st.setString(1, userRef)
            chatRef?.let { st.setString(2, it) }
            st.executeUpdate()
        }
        tokens
    }

    /**
     * A supergroup migration changes the chat id, which lives both in the ref
     * column and inside the sealed payload, so each row is resealed. The AAD is
     * the ref token and does not change, so the tokens stay valid throughout.
     */
    fun rewriteChatRef(oldChatId: Long, newChatId: Long): Int {
        val oldRef = crypto.ref(oldChatId.toString())
        val newRef = crypto.ref(newChatId.toString())
        val rows = query("SELECT * FROM request WHERE chat_ref = ?") { st -> st.setString(1, oldRef) }
        ds.connection.use { c ->
            c.autoCommit = false
            try {
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
                    c.prepareStatement("UPDATE request SET chat_ref = ?, payload = ? WHERE ref_token = ?").use { st ->
                        st.setString(1, newRef)
                        st.setBytes(2, resealed)
                        st.setString(3, r.refToken)
                        st.executeUpdate()
                    }
                }
                c.commit()
            } catch (e: Exception) {
                c.rollback(); throw e
            }
        }
        return rows.size
    }

    private fun query(sql: String, bind: (java.sql.PreparedStatement) -> Unit): List<Request> =
        ds.connection.use { c ->
            c.prepareStatement(sql).use { st ->
                bind(st)
                st.executeQuery().use { rs ->
                    val out = mutableListOf<Request>()
                    while (rs.next()) out += hydrate(rs)
                    out
                }
            }
        }

    private fun queryOne(
        sql: String,
        bind: (java.sql.PreparedStatement) -> Unit,
    ): Request? = ds.connection.use { c ->
        c.prepareStatement(sql).use { st ->
            bind(st)
            st.executeQuery().use { rs -> if (rs.next()) hydrate(rs) else null }
        }
    }

    /** Every field the domain needs comes out of the sealed payload. */
    private fun hydrate(rs: ResultSet): Request {
        val refToken = rs.getString("ref_token")
        val p = json.decodeFromString<Payload>(crypto.open(rs.getBytes("payload"), refToken))
        return Request(
            rowId = rs.getLong("row_id"),
            refToken = refToken,
            chatId = p.chatId,
            userId = p.userId,
            username = p.username,
            shortId = rs.getString("short_id"),
            side = Side.valueOf(p.side),
            statedCurrency = p.statedCurrency,
            statedAmount = BigDecimal(p.statedAmount),
            pair = CurrencyPair(p.base, p.quote),
            state = RequestState.valueOf(rs.getString("state")),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            expiresAt = rs.getTimestamp("expires_at").toInstant(),
        )
    }
}
