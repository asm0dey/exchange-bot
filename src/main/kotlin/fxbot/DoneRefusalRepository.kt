package fxbot

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Clock
import javax.sql.DataSource

/**
 * How many times one person's declared done about one pairing has been refused by the
 * other. Keyed in one direction — [DoneRefusals.refToken] is the declarer's request,
 * [DoneRefusals.peerRefToken] the refuser's — so the block that a second refusal earns
 * sits on whoever kept asking, and never on the person being pestered.
 *
 * Nothing here identifies anybody. Both columns are random ref tokens, and the rows die
 * with their requests ([dropClosed]), so this is deliberately not a durable record of two
 * people who do not want each other.
 */
class DoneRefusalRepository(
    ds: DataSource,
    private val clock: Clock = Clock.systemUTC(),
    // connectExposed(ds) is memoized per DataSource; see RequestRepository's constructor comment.
    private val db: Database = connectExposed(ds),
) {
    fun count(declarerToken: String, refuserToken: String): Int = transaction(db) {
        DoneRefusals.selectAll()
            .where {
                (DoneRefusals.refToken eq declarerToken) and (DoneRefusals.peerRefToken eq refuserToken)
            }
            .singleOrNull()
            ?.get(DoneRefusals.refusals)
            ?: 0
    }

    /**
     * Read-then-write rather than a SQL increment: this is a small table on a single-process
     * bot (ADR 0004), the surrounding transaction is the guard, and the plain form is the one
     * that is obviously right.
     */
    fun record(declarerToken: String, refuserToken: String): Int = transaction(db) {
        val next = count(declarerToken, refuserToken) + 1
        DoneRefusals.upsert {
            it[refToken] = declarerToken
            it[peerRefToken] = refuserToken
            it[refusals] = next
            it[refusedAt] = clock.instant()
        }
        next
    }

    /** Forgetting: every row naming one of this person's requests, whichever side it sits on. */
    fun deleteFor(tokens: List<String>): Int = transaction(db) {
        if (tokens.isEmpty()) {
            0
        } else {
            DoneRefusals.deleteWhere {
                (DoneRefusals.refToken inList tokens) or (DoneRefusals.peerRefToken inList tokens)
            }
        }
    }

    /**
     * Housekeeping: a row whose declarer's or refuser's request is no longer resting has
     * nothing left to block. Read-then-delete, for the reason [record] gives.
     */
    fun dropClosed(): Int = transaction(db) {
        val live = Requests.selectAll()
            .where { Requests.state eq RequestState.OPEN.name }
            .map { it[Requests.refToken] }
            .toSet()
        val dead = DoneRefusals.selectAll()
            .map { it[DoneRefusals.refToken] to it[DoneRefusals.peerRefToken] }
            .filter { (declarer, refuser) -> declarer !in live || refuser !in live }
        for ((declarer, refuser) in dead) {
            DoneRefusals.deleteWhere {
                (DoneRefusals.refToken eq declarer) and (DoneRefusals.peerRefToken eq refuser)
            }
        }
        dead.size
    }
}
