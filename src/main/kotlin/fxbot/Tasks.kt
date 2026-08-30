package fxbot

import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import javax.sql.DataSource

private val RETENTION: Duration = Duration.ofDays(90)

class Housekeeping(
    private val requests: RequestRepository,
    private val settings: ChatSettingsRepository,
    private val rates: RateService,
    private val log: MessageLogRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Lapses what is past its time in force, and prunes the message record. */
    fun sweep(): Int {
        val expired = requests.expireDue(clock.instant())
        log.prune(clock.instant().minus(RETENTION))
        return expired
    }

    suspend fun refreshRates() = rates.refresh(settings.allPairs())
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
        .execute { _, _ -> housekeeping.sweep() }
    val refresh = Tasks.recurring("refresh-rates", Schedules.fixedDelay(Duration.ofHours(24)))
        .execute { _, _ -> runBlocking { housekeeping.refreshRates() } }

    Scheduler.create(ds).startTasks(sweep, refresh).threads(2).build().start()
}
