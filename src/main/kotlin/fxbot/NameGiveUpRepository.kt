package fxbot

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Clock
import javax.sql.DataSource

/** What one side decided about one pairing. Two [OFFERED] rows mean both consented. */
enum class Stance { OFFERED, DECLINED }

/**
 * Consent for a name give-up, persisted rather than carried in a button (ADR 0007). A
 * hand-crafted callback payload claiming the other side agreed achieves nothing, because
 * this table is where agreement is read from.
 *
 * One row per DIRECTION of a pairing, keyed on the ordered token pair. A decline is read
 * in either direction: it covers the pairing symmetrically, so neither side is offered
 * the other again. Nothing here is a durable record of two people who don't want each
 * other — rows die with their requests ([dropClosed]), so someone who declines the same
 * person in a later interest will be offered them again.
 *
 * No name is ever stored here. The handles are looked up live when a give-up completes.
 */
class NameGiveUpRepository(
    ds: DataSource,
    private val crypto: Crypto,
    private val clock: Clock = Clock.systemUTC(),
    // connectExposed(ds) is memoized per DataSource; see RequestRepository's constructor comment.
    private val db: Database = connectExposed(ds),
) {
    fun record(refToken: String, peerRefToken: String, userId: Long, stance: Stance): Unit = transaction(db) {
        NameGiveUps.upsert {
            it[NameGiveUps.refToken] = refToken
            it[NameGiveUps.peerRefToken] = peerRefToken
            it[NameGiveUps.userRef] = crypto.ref(userId.toString())
            it[NameGiveUps.stance] = stance.name
            it[decidedAt] = clock.instant()
        }
    }

    fun stanceOf(refToken: String, peerRefToken: String): Stance? = transaction(db) {
        NameGiveUps.selectAll()
            .where { (NameGiveUps.refToken eq refToken) and (NameGiveUps.peerRefToken eq peerRefToken) }
            .singleOrNull()
            ?.let { Stance.valueOf(it[NameGiveUps.stance]) }
    }

    /** True when EITHER side declined — a decline covers the pairing symmetrically. */
    fun declined(a: String, b: String): Boolean =
        stanceOf(a, b) == Stance.DECLINED || stanceOf(b, a) == Stance.DECLINED

    fun bothOffered(a: String, b: String): Boolean =
        stanceOf(a, b) == Stance.OFFERED && stanceOf(b, a) == Stance.OFFERED

    fun deleteFor(userId: Long): Unit = transaction(db) {
        val userRef = crypto.ref(userId.toString())
        NameGiveUps.deleteWhere { NameGiveUps.userRef eq userRef }
    }

    /**
     * Housekeeping: a row whose own request or whose peer's request is no longer resting
     * has nothing left to consent about. Read-then-delete rather than a correlated
     * subquery — this is a small table on a single-process bot (ADR 0004), and the plain
     * form is the one that is obviously right.
     */
    fun dropClosed(): Int = transaction(db) {
        val live = Requests.selectAll()
            .where { Requests.state eq RequestState.OPEN.name }
            .map { it[Requests.refToken] }
            .toSet()
        val dead = NameGiveUps.selectAll()
            .map { it[NameGiveUps.refToken] to it[NameGiveUps.peerRefToken] }
            .filter { (mine, theirs) -> mine !in live || theirs !in live }
        for ((mine, theirs) in dead) {
            NameGiveUps.deleteWhere {
                (NameGiveUps.refToken eq mine) and (NameGiveUps.peerRefToken eq theirs)
            }
        }
        dead.size
    }
}
