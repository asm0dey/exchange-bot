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
     *
     * The set of bases to fetch still comes from configured pairs (that is what bounds
     * the network calls), but once a response comes back, every quote it carries is
     * persisted — not just the ones some chat has configured. open.er-api.com returns
     * ~160 rates per base; discarding the unconfigured ones meant a chat created after
     * startup had no rate for its pair until the next daily refresh, even though the
     * rate was already sitting in a response we'd fetched and thrown away.
     *
     * `clock.instant()` is read once per base, not per row, so every rate stored from
     * one response shares one `fetchedAt` — the staleness check in [status] reads that
     * column, and a smeared timestamp across a single fetch would be untidy.
     */
    suspend fun refresh(pairs: Set<CurrencyPair>) {
        for (base in pairs.map { it.base }.toSet()) {
            val rates = client.fetch(base) ?: continue
            val fetchedAt = clock.instant()
            for ((quote, rate) in rates) {
                repo.put(base, quote, rate, fetchedAt)
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
