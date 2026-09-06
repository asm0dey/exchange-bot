package fxbot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

private val logger = LoggerFactory.getLogger("fxbot.AnnouncementBatcher")

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

/**
 * Where a flush's output goes. The Telegram layer sends and records; a test collects.
 *
 * An implementation must be safe to call from any thread: announcements arrive holding
 * the batcher's flush lock and pings arrive without it, so two calls can overlap. It must
 * also never call back into [AnnouncementBatcher.flush] or
 * [AnnouncementBatcher.flushAllOnStartup] — that lock is a `Mutex`, which is not
 * reentrant, so re-entering it from inside a send would deadlock the batcher.
 *
 * THROWING is a supported outcome, and the only way to say the send did not happen: the
 * batcher keeps the pending rows and retries them on the next flush. A sink that swallows
 * a failed send is telling the batcher the chat was told when it was not.
 */
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
 *
 * That guarantee survives a failing send. A pending row is consumed only AFTER
 * [AnnouncementSink.deliver] returns, so a send that throws leaves the chat still owed
 * its announcement and the next flush — or the next restart — re-renders and retries it.
 * The cost of choosing that way round is that a sink which fails PART of a batch will see
 * the whole batch again: a chat may be told twice, which is recoverable, rather than
 * never, which is not. A failed send inside a window is caught and counted rather than
 * rethrown, because the scope is handed in from outside and may well be an ordinary
 * [CoroutineScope]: one escaping exception would cancel its job and silently stop every
 * later window for the life of the process.
 *
 * A ping has no such backstop — nothing about one is written down — so a failed ping is
 * counted and gone.
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

    // A pending row is read, rendered, sent and only then deleted, so two overlapping
    // flushes that both READ it would send the same message twice — deleting it is what
    // makes it stop existing, and that happens at the very end. The lock therefore has to
    // cover the read as well as the write, which is why `flush` takes it and the work
    // itself does not. The bot is a single process (ADR 0004), so one lock is enough.
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
                guarded("announcement flush") { flush(userId) }
            }
        }
    }

    fun enqueueAppeared(appeared: List<CounterpartyAppeared>) {
        for (a in appeared) {
            // `compute` is atomic for this key, and so is the `remove` the drain uses, so
            // a token can never land in a set the drain has already taken away. Opening
            // the window from INSIDE the same block is what closes the other half of it:
            // whichever side goes first, either the drain carries this token or a window
            // is open that will. Starting a coroutine in here is safe — it touches a
            // different map, and its body suspends on `delay` before it could reach this
            // one.
            appearedQueue.compute(a.userId) { _, queued ->
                val tokens = queued ?: linkedSetOf()
                tokens.add(a.refToken)
                appearedWindows.computeIfAbsent(a.userId) { openAppearedWindow(a.userId) }
                tokens
            }
        }
    }

    private fun openAppearedWindow(userId: Long): Job = scope.launch {
        delay(window.toMillis())
        appearedWindows.remove(userId)
        guarded("ping flush") { flushAppeared(userId) }
    }

    suspend fun flush(userId: Long) = announcing.withLock { deliverLocked(pending.allFor(userId)) }

    /** At startup, everything still owed — from before a restart — is re-rendered and sent. */
    suspend fun flushAllOnStartup() = announcing.withLock { deliverLocked(pending.all()) }

    /** The caller holds [announcing], and holds it across the read that produced [rows]. */
    private suspend fun deliverLocked(rows: List<PendingAnnouncement>) {
        if (rows.isEmpty()) return
        val cutoff = clock.instant().minus(maxAge)
        // An hour-late "someone just stated this" is noise, and the showing keeps working
        // silently regardless, so a stale row is dropped rather than sent.
        val (stale, live) = rows.partition { it.createdAt.isBefore(cutoff) }
        for (row in stale) pending.remove(row.chatRef, row.interestToken)
        if (stale.isNotEmpty()) logger.info("announcement flush: dropped_stale=${stale.size}")

        val announcements = mutableListOf<Announcement>()
        val owed = mutableListOf<PendingAnnouncement>()
        for ((chatId, forChat) in groupByChat(live)) {
            val chat = chats.get(chatId)
            val status = rateService.status(chat.pair)
            val resting = requests.resting(chatId)
            val shown = mutableListOf<Pair<PendingAnnouncement, ShownInterest>>()
            for (row in forChat) {
                val showing = requests.siblings(row.interestToken)
                    .firstOrNull { it.chatId == chatId && it.state == RequestState.OPEN }
                if (showing == null) {
                    // An announcement whose showings have all closed has nothing left to
                    // say, so this row is spent whatever becomes of the send.
                    pending.remove(row.chatRef, row.interestToken)
                    continue
                }
                shown += row to ShownInterest(
                    showing,
                    findCounterparties(showing, resting, status.rate, chat.tolerancePct),
                )
            }
            // One message per chat PER PERSON. A chat is a single grouping only when a
            // single person is behind everything in it; attributing two people's interests
            // to whoever happened to come first would say something plainly untrue.
            for ((_, group) in shown.groupBy { it.second.request.userId }) {
                val mine = group.map { it.second }
                val owner = mine.first().request
                announcements += Announcement(
                    chatId = chatId,
                    text = renderAnnouncement(mentionOf(owner), mine, status),
                    buttons = announcementButtons(mine),
                    refTokens = mine.map { it.request.refToken } + mine.flatMap { s -> s.found.map { it.request.refToken } },
                    userIds = mine.map { it.request.userId } + mine.flatMap { s -> s.found.map { it.request.userId } },
                )
            }
            owed += shown.map { it.first }
        }
        if (announcements.isEmpty()) return
        // Sent first, consumed second. A throw here leaves every row exactly where it was.
        sink.deliver(announcements, emptyList())
        for (row in owed) pending.remove(row.chatRef, row.interestToken)
        logger.info("announcement flush: sent=${announcements.size}")
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

    /**
     * Runs a window's work so that a failure costs that batch and nothing else. [label] is
     * a fixed literal at each call site and the exception's TYPE is a class name, so this
     * log line carries no chat, person, amount or message text.
     */
    private suspend fun guarded(label: String, body: suspend () -> Unit) {
        try {
            body()
        } catch (e: CancellationException) {
            // Our own scope shutting us down, not the work failing. Never swallowed.
            throw e
        } catch (e: Exception) {
            logger.warn("$label: outcome=failed type=${e.javaClass.simpleName}")
        }
    }
}
