package fxbot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * How long an announcement may wait before it stops being news. Declared once, here,
 * because the batcher and the housekeeping that prunes the same rows must agree on it.
 */
val PENDING_MAX_AGE: Duration = Duration.ofHours(1)

/** One message the bot owes one chat. Composed at flush time from live state. */
data class Announcement(
    val chatId: Long,
    val text: String,
    val buttons: List<Button>,
    val refTokens: List<String>,
    val userIds: List<Long>,
)

/** One "a counterparty appeared" message the bot owes one person on the no-names side. */
data class Ping(val userId: Long, val text: String, val buttons: List<Button>)

/** Where a flush's output goes. The Telegram layer sends and records; a test collects. */
fun interface AnnouncementSink {
    suspend fun deliver(announcements: List<Announcement>, pings: List<Ping>)
}

/**
 * A showing rests the moment it is created; only its announcement waits here. The first
 * private statement opens a 60-second window, everything stated before it fires goes out
 * as one message per chat per person, and then the window RESETS — a sliding window would
 * starve a busy person indefinitely, and a showing must never be more than a minute
 * behind the person who stated it.
 *
 * Pings batch on their own window keyed by RECIPIENT, not by whoever caused them:
 * somebody else's typing speed must not decide how many messages you get.
 *
 * Nothing here is ever replayed. What is persisted is which chat is owed an announcement
 * about which interest ([PendingAnnouncementRepository]); the text is re-rendered from
 * live state at every flush, so a restart inside the window cannot leave a showing
 * resting in a chat that was never told, and cannot announce a request that has since
 * closed.
 */
class AnnouncementBatcher(
    private val requests: RequestRepository,
    private val chats: ChatSettingsRepository,
    private val pending: PendingAnnouncementRepository,
    private val interests: InterestService,
    private val rateService: RateService,
    private val sink: AnnouncementSink,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val window: Duration = Duration.ofSeconds(60),
    private val maxAge: Duration = PENDING_MAX_AGE,
) {
    private val announceWindows = ConcurrentHashMap<Long, Job>()
    private val appearedWindows = ConcurrentHashMap<Long, Job>()
    private val appearedQueue = ConcurrentHashMap<Long, MutableSet<String>>()

    // A pending row is read and then deleted, so two flushes overlapping on the same row
    // would send the same message twice. The bot is a single process (ADR 0004), so one
    // lock is enough to make that impossible — a startup flush cannot race a window.
    private val announcing = Mutex()

    /** Opens the window if it is not already open. The statement itself is already persisted. */
    fun enqueueAnnouncement(userId: Long) {
        // computeIfAbsent, not put: a second statement inside the window joins the batch
        // rather than pushing its deadline out.
        announceWindows.computeIfAbsent(userId) {
            scope.launch {
                delay(window.toMillis())
                // Cleared BEFORE the flush, so a statement made while it runs opens a
                // fresh window instead of being swallowed by the one that is closing.
                announceWindows.remove(userId)
                flush(userId)
            }
        }
    }

    fun enqueueAppeared(appeared: List<CounterpartyAppeared>) {
        for (a in appeared) {
            appearedQueue.computeIfAbsent(a.userId) { ConcurrentHashMap.newKeySet() }.add(a.refToken)
            appearedWindows.computeIfAbsent(a.userId) {
                scope.launch {
                    delay(window.toMillis())
                    appearedWindows.remove(a.userId)
                    flushAppeared(a.userId)
                }
            }
        }
    }

    suspend fun flush(userId: Long) = deliver(pending.allFor(userId))

    /** At startup, everything still owed — from before a restart — is re-rendered and sent. */
    suspend fun flushAllOnStartup() = deliver(pending.all())

    private suspend fun deliver(rows: List<PendingAnnouncement>): Unit = announcing.withLock {
        if (rows.isEmpty()) return@withLock
        val cutoff = clock.instant().minus(maxAge)
        val announcements = mutableListOf<Announcement>()
        // An hour-late "someone just stated this" is noise, and the showing keeps working
        // silently regardless, so a stale row is dropped rather than sent.
        val (stale, live) = rows.partition { it.createdAt.isBefore(cutoff) }
        for (row in stale) pending.remove(row.chatRef, row.interestToken)

        for ((chatId, forChat) in groupByChat(live)) {
            val chat = chats.get(chatId)
            val status = rateService.status(chat.pair)
            val resting = requests.resting(chatId)
            val shown = forChat.mapNotNull { row ->
                val showing = requests.siblings(row.interestToken)
                    .firstOrNull { it.chatId == chatId && it.state == RequestState.OPEN }
                    ?: return@mapNotNull null
                ShownInterest(showing, findCounterparties(showing, resting, status.rate, chat.tolerancePct))
            }
            // Every row for this chat is consumed whether or not it survived the re-render:
            // an announcement whose showings have all closed has nothing left to say.
            for (row in forChat) pending.remove(row.chatRef, row.interestToken)
            // One message per chat PER PERSON. A chat is a single grouping only when a
            // single person is behind everything in it; attributing two people's interests
            // to whoever happened to come first would say something plainly untrue.
            for ((_, mine) in shown.groupBy { it.request.userId }) {
                val owner = mine.first().request
                announcements += Announcement(
                    chatId = chatId,
                    text = renderAnnouncement(mentionOf(owner), mine, status),
                    buttons = announcementButtons(mine),
                    refTokens = mine.map { it.request.refToken } + mine.flatMap { s -> s.found.map { it.request.refToken } },
                    userIds = mine.map { it.request.userId } + mine.flatMap { s -> s.found.map { it.request.userId } },
                )
            }
        }
        if (announcements.isNotEmpty()) sink.deliver(announcements, emptyList())
    }

    /** One message per recipient, whatever else was going on. */
    suspend fun flushAppeared(userId: Long) {
        val tokens = appearedQueue.remove(userId).orEmpty()
        if (tokens.isEmpty()) return
        val mine = tokens.mapNotNull { requests.byRefToken(it) }
            .filter { it.state == RequestState.OPEN }
            .map { ShownInterest(it, interests.counterparties(it)) }
            .filter { it.found.isNotEmpty() }
        if (mine.isEmpty()) return
        sink.deliver(emptyList(), listOf(Ping(userId, renderAppeared(mine), appearedButtons(mine))))
    }

    /** Pending rows carry a chat REF; the real id comes out of the interest's own showings. */
    private fun groupByChat(rows: List<PendingAnnouncement>): Map<Long, List<PendingAnnouncement>> {
        val out = mutableMapOf<Long, MutableList<PendingAnnouncement>>()
        for (row in rows) {
            // Siblings in every state, so a showing that has since closed still resolves
            // the chat this row is owed to.
            val chatId = requests.siblings(row.interestToken)
                .map { it.chatId }
                .firstOrNull { it != NO_NAMES_CHAT_ID && pending.isFor(row, it) }
            if (chatId == null) {
                // The interest is gone entirely; there is nothing left to announce.
                pending.remove(row.chatRef, row.interestToken)
                continue
            }
            out.getOrPut(chatId) { mutableListOf() }.add(row)
        }
        return out
    }
}
