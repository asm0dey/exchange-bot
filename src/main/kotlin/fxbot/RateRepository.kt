package fxbot

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.math.BigDecimal
import java.time.Instant
import javax.sql.DataSource

data class CachedRate(val rate: BigDecimal, val fetchedAt: Instant)

/**
 * Public data — the one table with nothing encrypted in it: no Tink, no payload
 * column, no refs. `base`/`quote` are stored, and read back, in the clear.
 */
class RateRepository(
    ds: DataSource,
    // connectExposed(ds) is memoized per DataSource; see RequestRepository's constructor comment.
    private val db: Database = connectExposed(ds),
) {
    fun get(base: String, quote: String): CachedRate? = transaction(db) {
        FxRates.selectAll()
            .where { (FxRates.base eq base) and (FxRates.quote eq quote) }
            .singleOrNull()
            ?.let { CachedRate(it[FxRates.rate], it[FxRates.fetchedAt]) }
    }

    /**
     * `upsert()` with no explicit conflict-key columns falls back to the table's
     * declared `PrimaryKey`, which for [FxRates] is the composite `(base, quote)` —
     * confirmed against this repository's own test, since Task 6 only exercised a
     * single-column key.
     */
    fun put(base: String, quote: String, rate: BigDecimal, at: Instant) {
        transaction(db) {
            FxRates.upsert {
                it[FxRates.base] = base
                it[FxRates.quote] = quote
                it[FxRates.rate] = rate
                it[fetchedAt] = at
            }
        }
    }
}
