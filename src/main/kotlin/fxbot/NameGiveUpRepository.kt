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
    /**
     * How [dropClosed] gets back to a Telegram id so the person waiting can be told. The
     * `user_ref` on this table is a one-way MAC and can never be reversed; the offerer's
     * OWN request carries their id inside its sealed payload, and that is the only
     * readable copy. Defaulted so every construction site stays a repository over a
     * DataSource, and shares the memoized [db] rather than registering a second one.
     * Only ever READ through — a second [RequestRepository] instance has its own
     * short-id allocation lock, which would not be a guard if anything here created rows.
     */
    private val requests: RequestRepository = RequestRepository(ds, crypto, clock, db),
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
     *
     * Returns the people who should be told, each once: somebody who agreed to pass names
     * and is still waiting for the answer, whose peer's request closed underneath them.
     * They get their id out of their OWN still-resting request's sealed payload — the
     * `user_ref` column here is a one-way MAC and is never a route back to a person.
     *
     * Three kinds of row are dropped silently. One whose OWN request has gone too: there
     * is nobody left to tell and their interest is closed anyway. A decline: the person's
     * own "no" is what ended it, so they are not waiting on anything. And one the peer
     * already ANSWERED — when the reciprocal row is `OFFERED` too, the names were passed
     * and nothing deletes these rows at disclosure, so they survive as `OFFERED` until a
     * request closes. Telling somebody who is holding the other person's handle that
     * "nothing was passed on" would be false, and this is the ordinary path: any later
     * cancel, done or lapse of the peer's showing reaches it.
     */
    fun dropClosed(): List<Long> = transaction(db) {
        val live = Requests.selectAll()
            .where { Requests.state eq RequestState.OPEN.name }
            .map { it[Requests.refToken] }
            .toSet()
        val rows = NameGiveUps.selectAll()
            .map {
                Triple(it[NameGiveUps.refToken], it[NameGiveUps.peerRefToken], Stance.valueOf(it[NameGiveUps.stance]))
            }
        // Every direction that says yes, so the reciprocal of a row can be looked up
        // without a second query — this is the one read the whole method works from.
        val offered = rows.filter { (_, _, stance) -> stance == Stance.OFFERED }
            .map { (mine, theirs, _) -> mine to theirs }
            .toSet()
        val dead = rows.filter { (mine, theirs, _) -> mine !in live || theirs !in live }
        val bereaved = dead
            .filter { (mine, theirs, stance) ->
                stance == Stance.OFFERED && mine in live && theirs !in live && (theirs to mine) !in offered
            }
            .mapNotNull { (mine, _, _) -> requests.byRefToken(mine)?.userId }
            .distinct()
        for ((mine, theirs, _) in dead) {
            NameGiveUps.deleteWhere {
                (NameGiveUps.refToken eq mine) and (NameGiveUps.peerRefToken eq theirs)
            }
        }
        bereaved
    }
}
