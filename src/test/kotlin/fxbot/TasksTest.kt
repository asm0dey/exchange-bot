package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.bigdecimal.shouldBeEqualIgnoringScale
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource

private val EURRUB = CurrencyPair("EUR", "RUB")
private val T0 = Instant.parse("2026-08-30T12:00:00Z")
private const val BODY = """{"result":"success","base_code":"EUR","rates":{"RUB":99.98}}"""

private fun stubRates(ds: DataSource, clock: Clock) = RateService(
    RateClient(HttpClient(MockEngine { respond(BODY, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) })),
    RateRepository(ds), clock,
)

/**
 * Builds a [Housekeeping] over one in-memory database. Every collaborator is defaulted so
 * a test names only the ones it actually cares about — the constructor is eight arguments
 * wide and a wall of them at each call site hides which one the test is about.
 */
private fun housekeepingWith(
    ds: DataSource,
    crypto: Crypto,
    clock: Clock,
    requests: RequestRepository,
    settings: ChatSettingsRepository = ChatSettingsRepository(ds, crypto, clock),
    rates: RateService = stubRates(ds, clock),
    giveUps: NameGiveUpRepository = NameGiveUpRepository(ds, crypto, clock),
    pending: PendingAnnouncementRepository = PendingAnnouncementRepository(ds, crypto, clock),
    onGiveUpDied: suspend (List<Long>) -> Unit = {},
    onClosed: suspend (List<String>) -> Unit = {},
) = Housekeeping(
    requests, settings, rates, MessageLogRepository(ds, crypto, clock),
    giveUps, pending, clock, onClosed, onGiveUpDied,
)

private fun housekeeping(name: String, at: Instant): Pair<Housekeeping, RequestRepository> {
    val ds = memDataSource(name)
    migrate(ds)
    val crypto = testCrypto()
    val clock = Clock.fixed(at, ZoneOffset.UTC)
    val requests = RequestRepository(ds, crypto, Clock.fixed(T0, ZoneOffset.UTC))
    return housekeepingWith(ds, crypto, clock, requests) to requests
}

class TasksTest : StringSpec({
    "the sweep lapses requests past their time in force" {
        val (hk, repo) = housekeeping("sweep", T0.plusSeconds(8 * 86_400))
        repo.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        hk.sweep() shouldBe 1
        repo.resting(-100L) shouldHaveSize 0
    }
    "the sweep leaves live requests alone" {
        val (hk, repo) = housekeeping("sweepalive", T0.plusSeconds(86_400))
        repo.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        hk.sweep() shouldBe 0
        repo.resting(-100L) shouldHaveSize 1
    }
    "the refresh caches a rate for every pair a chat has configured" {
        val ds = memDataSource("refresh")
        migrate(ds)
        val crypto = testCrypto()
        val clock = Clock.fixed(T0, ZoneOffset.UTC)
        val settings = ChatSettingsRepository(ds, crypto, clock)
        settings.save(ChatSettings(-100L, EURRUB, 20, 7))
        val rateRepo = RateRepository(ds)
        val rates = RateService(
            RateClient(HttpClient(MockEngine { respond(BODY, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) })),
            rateRepo, clock,
        )
        housekeepingWith(ds, crypto, clock, RequestRepository(ds, crypto, clock), settings, rates).refreshRates()
        // BigDecimal.equals is scale-sensitive; the DB read comes back scale-10
        // (DECIMAL(30, 10)) regardless of the scale it was written with — same
        // reason RateServiceTest compares by value, not by `shouldBe`.
        rateRepo.get("EUR", "RUB")!!.rate.shouldBeEqualIgnoringScale(BigDecimal("99.98"))
    }
    "the refresh also prices a pair only the bot-side is using" {
        val ds = memDataSource("refreshnochat")
        migrate(ds)
        val crypto = testCrypto()
        val clock = Clock.fixed(T0, ZoneOffset.UTC)
        val requests = RequestRepository(ds, crypto, clock)
        requests.create(NO_CHAT_ID, 1L, "bob", Side.OFFER, "CHF", BigDecimal("10"), CurrencyPair("CHF", "JPY"), 7, "i1")
        val rateRepo = RateRepository(ds)
        val bases = mutableListOf<String>()
        val client = RateClient(HttpClient(MockEngine { request ->
            bases += request.url.encodedPath.substringAfterLast('/')
            respond(BODY, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }))
        val settings = ChatSettingsRepository(ds, crypto, clock).also { it.save(ChatSettings(-100L, EURRUB, 20, 7)) }
        housekeepingWith(ds, crypto, clock, requests, settings, RateService(client, rateRepo, clock)).refreshRates()
        bases.toSet() shouldBe setOf("EUR", "CHF")
    }
    "the sweep drops give-up rows and pending announcements whose requests have closed" {
        val ds = memDataSource("sweepdrops")
        migrate(ds)
        val crypto = testCrypto()
        val sweepAt = T0.plusSeconds(8 * 86_400)
        val clock = Clock.fixed(sweepAt, ZoneOffset.UTC)
        val requests = RequestRepository(ds, crypto, Clock.fixed(T0, ZoneOffset.UTC))
        val a = requests.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7, "i1")
        // Stated at the sweep's own instant, so these are still resting when it runs and
        // everything hanging off them has to survive it.
        val fresh = RequestRepository(ds, crypto, clock)
        val b = fresh.create(-100L, 2L, "ann", Side.BID, "EUR", BigDecimal("1"), EURRUB, 7, "i2")
        val c = fresh.create(-100L, 3L, "cat", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7, "i3")
        val giveUps = NameGiveUpRepository(ds, crypto, clock).also {
            it.record(a.refToken, "gone", 1L, Stance.OFFERED)
            it.record(b.refToken, c.refToken, 2L, Stance.OFFERED)
        }
        PendingAnnouncementRepository(ds, crypto, Clock.fixed(T0, ZoneOffset.UTC)).add(-100L, "i1", 1L)
        val pending = PendingAnnouncementRepository(ds, crypto, clock).also { it.add(-100L, "i2", 2L) }
        val lapsed = mutableListOf<String>()
        val hk = housekeepingWith(ds, crypto, clock, requests, giveUps = giveUps, pending = pending) { lapsed += it }
        hk.sweep() shouldBe 1
        lapsed shouldBe listOf(a.refToken)
        giveUps.stanceOf(a.refToken, "gone") shouldBe null
        pending.all().single().interestToken shouldBe "i2"
        // The rows whose requests are still resting are untouched.
        giveUps.stanceOf(b.refToken, c.refToken) shouldBe Stance.OFFERED
    }
    "the sweep tells whoever was waiting when the other side's interest closed" {
        val ds = memDataSource("sweepbereaved")
        migrate(ds)
        val crypto = testCrypto()
        val clock = Clock.fixed(T0, ZoneOffset.UTC)
        val requests = RequestRepository(ds, crypto, clock)
        val mine = requests.create(NO_CHAT_ID, 7L, "bob", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7, "i1")
        val theirs = requests.create(NO_CHAT_ID, 8L, "ann", Side.BID, "EUR", BigDecimal("1"), EURRUB, 7, "i2")
        val giveUps = NameGiveUpRepository(ds, crypto, clock).also {
            it.record(mine.refToken, theirs.refToken, 7L, Stance.OFFERED)
        }
        requests.closeInterest("i2", RequestState.DONE)
        val told = mutableListOf<List<Long>>()
        val lapsed = mutableListOf<String>()
        val hk = housekeepingWith(
            ds, crypto, clock, requests, giveUps = giveUps,
            onGiveUpDied = { told += it }, onClosed = { lapsed += it },
        )
        hk.sweep() shouldBe 0
        told shouldBe listOf(listOf(7L))
        // Nothing lapsed, so the message-rewriting pass was not asked to do anything.
        lapsed shouldHaveSize 0
    }
    "a hook that throws neither fails the sweep nor stops the other hook" {
        val ds = memDataSource("sweephookfails")
        migrate(ds)
        val crypto = testCrypto()
        val sweepAt = T0.plusSeconds(8 * 86_400)
        val clock = Clock.fixed(sweepAt, ZoneOffset.UTC)
        // One showing stated at T0 with a 7-day time in force: it lapses in this sweep,
        // so onClosed fires — and throws.
        val lapsing = RequestRepository(ds, crypto, Clock.fixed(T0, ZoneOffset.UTC))
        lapsing.create(-100L, 9L, "zed", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        // A give-up whose peer closed, whose offerer is still resting: onGiveUpDied fires
        // AFTER the throwing hook, and its rows are already gone by then.
        val requests = RequestRepository(ds, crypto, clock)
        val mine = requests.create(NO_CHAT_ID, 7L, "bob", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7, "i1")
        val theirs = requests.create(NO_CHAT_ID, 8L, "ann", Side.BID, "EUR", BigDecimal("1"), EURRUB, 7, "i2")
        val giveUps = NameGiveUpRepository(ds, crypto, clock).also {
            it.record(mine.refToken, theirs.refToken, 7L, Stance.OFFERED)
        }
        requests.closeInterest("i2", RequestState.DONE)
        val told = mutableListOf<List<Long>>()
        val hk = housekeepingWith(
            ds, crypto, clock, lapsing, giveUps = giveUps,
            onGiveUpDied = { told += it },
            onClosed = { throw IllegalStateException("telegram is down") },
        )

        hk.sweep() shouldBe 1
        told shouldBe listOf(listOf(7L))
    }
    "a throwing give-up notice does not fail the sweep either" {
        val ds = memDataSource("sweepgiveuphookfails")
        migrate(ds)
        val crypto = testCrypto()
        val clock = Clock.fixed(T0, ZoneOffset.UTC)
        val requests = RequestRepository(ds, crypto, clock)
        val mine = requests.create(NO_CHAT_ID, 7L, "bob", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7, "i1")
        val theirs = requests.create(NO_CHAT_ID, 8L, "ann", Side.BID, "EUR", BigDecimal("1"), EURRUB, 7, "i2")
        val giveUps = NameGiveUpRepository(ds, crypto, clock).also {
            it.record(mine.refToken, theirs.refToken, 7L, Stance.OFFERED)
        }
        requests.closeInterest("i2", RequestState.DONE)
        val hk = housekeepingWith(
            ds, crypto, clock, requests, giveUps = giveUps,
            onGiveUpDied = { throw IllegalStateException("telegram is down") },
        )

        // A retry could not recover anything — the rows are gone — so the task reports
        // its real result rather than asking db-scheduler to run the whole sweep again.
        hk.sweep() shouldBe 0
    }
    "startScheduler registers both tasks with no task_data" {
        // db-scheduler's task_data is an unencrypted BYTEA — nothing chat- or
        // pair-identifying may ever land in it. `Tasks.recurring(name, schedule)`
        // (no dataClass argument) structurally can't carry data today, but this
        // pins that down against a future switch to a data-carrying overload:
        // `Scheduler.start()` runs `executeOnStartup()` synchronously, so both
        // rows already exist by the time `startScheduler` returns.
        val ds = memDataSource("scheduled")
        migrate(ds)
        val crypto = testCrypto()
        val clock = Clock.fixed(T0, ZoneOffset.UTC)
        startScheduler(ds, housekeepingWith(ds, crypto, clock, RequestRepository(ds, crypto, clock)))

        val taskData = ds.connection.use { conn ->
            conn.createStatement().executeQuery("SELECT task_name, task_data FROM scheduled_tasks").use { rs ->
                buildMap {
                    while (rs.next()) put(rs.getString("task_name"), rs.getBytes("task_data"))
                }
            }
        }
        taskData.keys shouldBe setOf("sweep", "refresh-rates")
        taskData.values.all { it == null } shouldBe true
    }
})
