package fxbot

import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import javax.sql.DataSource

private val RETENTION: Duration = Duration.ofDays(90)
private val logger = LoggerFactory.getLogger("fxbot.Housekeeping")

class Housekeeping(
    private val requests: RequestRepository,
    private val settings: ChatSettingsRepository,
    private val rates: RateService,
    private val log: MessageLogRepository,
    private val giveUps: NameGiveUpRepository,
    private val pending: PendingAnnouncementRepository,
    private val clock: Clock = Clock.systemUTC(),
    /** Hands the lapsed tokens to the message-editing pass. Defaulted so tests need no bot. */
    private val onClosed: suspend (List<String>) -> Unit = {},
    /** Tells whoever was still waiting on a give-up whose peer request closed. Names nobody. */
    private val onGiveUpDied: suspend (List<Long>) -> Unit = {},
) {
    /**
     * Lapses what is past its time in force, prunes the message record, and drops the rows
     * that only made sense while their requests were resting. Counts and fixed labels only —
     * no chat, person or request identity belongs in this log line.
     *
     * Order matters: the lapsing runs first, so the two `dropClosed` passes below see the
     * showings that just expired as closed and clean up after them in the same sweep.
     */
    suspend fun sweep(): Int {
        val expired = requests.expireDue(clock.instant())
        val pruned = log.prune(clock.instant().minus(RETENTION))
        val bereaved = giveUps.dropClosed()
        val stalePending = pending.dropClosed() + pending.dropOlderThan(clock.instant().minus(PENDING_MAX_AGE))
        logger.info("sweep: expired=${expired.size} pruned=$pruned giveUpsTold=${bereaved.size} pending=$stalePending")
        if (expired.isNotEmpty()) onClosed(expired)
        if (bereaved.isNotEmpty()) onGiveUpDied(bereaved)
        return expired.size
    }

    /**
     * Chat pairs AND the pairs resting on a no-names basis — a pair no chat uses would
     * otherwise never get a reference rate. The feed is per base currency, so the cost is
     * one GET per distinct base per day, not per pair.
     */
    suspend fun refreshRates() = rates.refresh(settings.allPairs() + requests.noNamesPairs())
}

/**
 * Both recurring tasks carry no data: db-scheduler stores task_data as a
 * plaintext BLOB, and nothing identifying belongs there. The sweep is global
 * (it doesn't need to know which chat), and the rate refresh enumerates the
 * configured pairs itself via [ChatSettingsRepository.allPairs] rather than
 * having a pair handed to it.
 */
fun startScheduler(ds: DataSource, housekeeping: Housekeeping) {
    val sweep = Tasks.recurring("sweep", Schedules.fixedDelay(Duration.ofHours(24)))
        .execute { _, _ -> runBlocking { housekeeping.sweep() } }
    val refresh = Tasks.recurring("refresh-rates", Schedules.fixedDelay(Duration.ofHours(24)))
        .execute { _, _ -> runBlocking { housekeeping.refreshRates() } }

    Scheduler.create(ds).startTasks(sweep, refresh).threads(2).build().start()
}
