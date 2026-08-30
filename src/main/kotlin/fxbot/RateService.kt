package fxbot

import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val STALE_AFTER: Duration = Duration.ofDays(7)

/**
 * The three degradation states, from best to worst: a fresh cached rate; the feed unreachable
 * but a cached rate present (still usable, but old enough to say so); no cached rate
 * at all (cross-currency matching is simply unavailable — same-denomination matching
 * needs no rate and is unaffected).
 */
sealed interface RateStatus {
    val rate: BigDecimal?

    data class Fresh(override val rate: BigDecimal) : RateStatus
    data class Stale(override val rate: BigDecimal, val fetchedAt: Instant) : RateStatus
    data object Unavailable : RateStatus { override val rate: BigDecimal? get() = null }
}

class RateService(
    private val client: RateClient,
    private val repo: RateRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * A failed fetch leaves whatever is cached exactly where it is — `client.fetch`
     * returns null on any feed error and this loop simply moves on, so a bad refresh
     * never writes, and therefore never overwrites or erases, a cached rate.
     */
    suspend fun refresh(pairs: Set<CurrencyPair>) {
        for (base in pairs.map { it.base }.toSet()) {
            val rates = client.fetch(base) ?: continue
            for (pair in pairs.filter { it.base == base }) {
                rates[pair.quote]?.let { repo.put(pair.base, pair.quote, it, clock.instant()) }
            }
        }
    }

    fun status(pair: CurrencyPair): RateStatus {
        val cached = repo.get(pair.base, pair.quote) ?: return RateStatus.Unavailable
        val age = Duration.between(cached.fetchedAt, clock.instant())
        return if (age > STALE_AFTER) RateStatus.Stale(cached.rate, cached.fetchedAt)
        else RateStatus.Fresh(cached.rate)
    }
}
