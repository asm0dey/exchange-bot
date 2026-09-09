package fxbot

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private val T0 = Instant.parse("2026-09-06T12:00:00Z")
private val EURRUB = CurrencyPair("EUR", "RUB")

/**
 * Everything a batcher needs. `DB_CLOSE_DELAY=-1` keeps an in-memory database alive for
 * the whole JVM, so the prefix keeps every name in this file to itself.
 *
 * The repositories that stamp rows run on T0 while the batcher's own clock runs at [at],
 * which is what lets a test age a pending row without aging anything else.
 */
private class BatchFixture(
    name: String,
    at: Instant = T0,
    val window: Duration = Duration.ofSeconds(60),
    /** How long a send takes, so two flushes can be made to genuinely overlap. */
    val sinkDelayMs: Long = 0,
) {
    val ds = memDataSource("batch_$name").also { migrate(it) }
    val crypto = testCrypto()
    val clock: Clock = Clock.fixed(at, ZoneOffset.UTC)
    val stamped: Clock = Clock.fixed(T0, ZoneOffset.UTC)
    val requests = RequestRepository(ds, crypto, stamped)
    val chats = ChatSettingsRepository(ds, crypto, clock)
    val people = PersonSettingsRepository(ds, crypto, clock)
    val giveUps = NameGiveUpRepository(ds, crypto, clock)
    val pending = PendingAnnouncementRepository(ds, crypto, stamped)
    val rateRepo = RateRepository(ds).also { it.put("EUR", "RUB", BigDecimal("99.98"), T0) }
    val client = RateClient(HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }))
    val rates = RateService(client, rateRepo, clock)
    val interests = InterestService(requests, chats, people, rates, client, giveUps, pending, MembershipProbe { _, _ -> true })

    // Written from a window coroutine on Dispatchers.Default and read from the test thread.
    val announcements = CopyOnWriteArrayList<Announcement>()
    val pings = CopyOnWriteArrayList<Ping>()
    /** Counted before the send is allowed to fail, so a test can see an attempt it never saw the result of. */
    val sinkCalls = AtomicInteger()
    @Volatile
    var sinkFails = false
    val sink = AnnouncementSink { a, p ->
        sinkCalls.incrementAndGet()
        delay(sinkDelayMs)
        check(!sinkFails) { "the send failed" }
        announcements += a
        pings += p
    }
    val batcher = build()

    fun chat(id: Long) = apply { chats.save(ChatSettings(id, EURRUB, 20, 7)) }

    /**
     * A second batcher over the same database and the same keys, built from its own
     * repository instances: nothing this process held in memory survives into it. This is
     * what a restart looks like from the announcement's point of view.
     */
    fun build(): AnnouncementBatcher {
        val requests = RequestRepository(ds, crypto, stamped)
        val chats = ChatSettingsRepository(ds, crypto, clock)
        val people = PersonSettingsRepository(ds, crypto, clock)
        val giveUps = NameGiveUpRepository(ds, crypto, clock)
        val pending = PendingAnnouncementRepository(ds, crypto, stamped)
        val rates = RateService(client, RateRepository(ds), clock)
        val interests =
            InterestService(requests, chats, people, rates, client, giveUps, pending, MembershipProbe { _, _ -> true })
        return AnnouncementBatcher(
            requests, chats, pending, interests, rates, sink,
            CoroutineScope(Dispatchers.Default), clock, window = window,
        )
    }

    suspend fun awaitUntil(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val started = System.nanoTime()
        while ((System.nanoTime() - started) / 1_000_000 < timeoutMs) {
            if (cond()) return
            delay(5)
        }
        error("$what did not happen within ${timeoutMs}ms")
    }

    /** Waits for the window to fire, and answers how long that took from [since]. */
    suspend fun awaitAnnouncements(since: Long): Long {
        awaitUntil("the announcement window") { announcements.isNotEmpty() }
        return (System.nanoTime() - since) / 1_000_000
    }
}

class AnnouncementBatcherTest : StringSpec({
    "everything stated inside one window is one message per chat" {
        val f = BatchFixture("onepermessage").chat(-100L).chat(-200L)
        f.interests.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        f.interests.state(1L, "bob", Verb.SELL, "20", "EUR", "RUB")
        f.batcher.flush(1L)
        f.announcements shouldHaveSize 2
        f.announcements.map { it.chatId }.toSet() shouldBe setOf(-100L, -200L)
        // Each message carries every interest it covers, and every ref token it names.
        f.announcements.first().text shouldContain "10"
        f.announcements.first().text shouldContain "20"
        f.announcements.first().refTokens shouldHaveSize 2
    }

    "the message says the bot is showing these on someone's behalf" {
        val f = BatchFixture("onbehalf").chat(-100L)
        f.interests.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        f.batcher.flush(1L)
        f.announcements.single().text shouldContain "on @bob's behalf"
    }

    "a flush empties the queue, so a second flush says nothing" {
        val f = BatchFixture("flushempties").chat(-100L)
        f.interests.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        f.batcher.flush(1L)
        f.announcements.clear()
        f.batcher.flush(1L)
        f.announcements.shouldBeEmpty()
        f.pending.all().shouldBeEmpty()
    }

    "statements inside the window are held, and then go out together" {
        val f = BatchFixture("holds", window = Duration.ofMillis(400)).chat(-100L)
        f.interests.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        val opened = System.nanoTime()
        f.batcher.enqueueAnnouncement(1L)
        delay(100)
        f.interests.state(1L, "bob", Verb.SELL, "20", "EUR", "RUB")
        f.batcher.enqueueAnnouncement(1L)
        delay(100)
        // Still well inside the window: neither statement has been sent on its own.
        f.announcements.shouldBeEmpty()
        f.awaitAnnouncements(opened)
        f.announcements shouldHaveSize 1
        f.announcements.single().refTokens shouldHaveSize 2
    }

    "the window is a tumbling one: a second statement joins the batch without extending it" {
        val f = BatchFixture("tumbling", window = Duration.ofMillis(400)).chat(-100L)
        f.interests.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        val opened = System.nanoTime()
        f.batcher.enqueueAnnouncement(1L)
        delay(250)
        f.interests.state(1L, "bob", Verb.SELL, "20", "EUR", "RUB")
        f.batcher.enqueueAnnouncement(1L) // must NOT push the deadline out
        val elapsedMs = f.awaitAnnouncements(opened)
        // A window that slid would have been reset at 250ms and could not fire before 650ms.
        (elapsedMs < 650) shouldBe true
        f.announcements shouldHaveSize 1
        f.announcements.single().refTokens shouldHaveSize 2
    }

    "one recipient gets one ping however many counterparties appeared at once" {
        val f = BatchFixture("onepingeach")
        f.interests.state(1L, "bob", Verb.SELL, "1000", "EUR", "RUB")
        val a = f.interests.state(2L, "ann", Verb.BUY, "1000", "EUR", "RUB") as InterestResult.Stated
        val c = f.interests.state(3L, "cat", Verb.BUY, "1000", "EUR", "RUB") as InterestResult.Stated
        f.batcher.enqueueAppeared(a.appeared)
        f.batcher.enqueueAppeared(c.appeared)
        f.batcher.flushAppeared(1L)
        f.pings shouldHaveSize 1
        f.pings.single().userId shouldBe 1L
        // One message, and both of them are in it.
        val text = f.pings.single().text
        text shouldContain "sell 1,000 EUR"
        Regex("buy 1,000 EUR").findAll(text).count() shouldBe 2
        // One done per counterparty now, each pairing bob's own row with theirs.
        val mine = f.requests.resting(NO_CHAT_ID).single { it.userId == 1L }
        f.pings.single().buttons shouldHaveSize 2
        f.pings.single().buttons.map { it.data } shouldContainExactlyInAnyOrder listOf(
            Cb.done(mine.refToken, a.interest.refToken),
            Cb.done(mine.refToken, c.interest.refToken),
        )
        // Both counterparties are named, and both are recorded so /forget can reach them.
        text shouldContain "@ann"
        text shouldContain "@cat"
        f.pings.single().refTokens shouldContainExactlyInAnyOrder listOf(
            mine.refToken, a.interest.refToken, c.interest.refToken,
        )
        f.pings.single().userIds shouldContainExactlyInAnyOrder listOf(1L, 2L, 3L)
    }

    "an announcement is re-rendered from live state, not replayed" {
        val f = BatchFixture("rerender").chat(-100L)
        val r = f.interests.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB") as InterestResult.Stated
        // A counterparty turns up in that chat between the statement and the flush.
        f.requests.create(-100L, 2L, "ann", Side.BID, "EUR", BigDecimal("10"), EURRUB, 7)
        f.batcher.flush(1L)
        f.announcements.single().text shouldContain "@ann"
        f.announcements.single().refTokens shouldHaveSize 2
        r.shown.flatMap { it.found }.shouldBeEmpty() // nothing was known at statement time
    }

    "an announcement whose showings have all closed is dropped" {
        val f = BatchFixture("dropclosed").chat(-100L)
        val r = f.interests.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB") as InterestResult.Stated
        f.requests.closeInterest(r.interest.interestToken!!, RequestState.CANCELLED)
        f.batcher.flush(1L)
        f.announcements.shouldBeEmpty()
        f.pending.all().shouldBeEmpty()
    }

    "an announcement older than an hour is dropped, and the showing keeps working silently" {
        val f = BatchFixture("dropstale", at = T0.plusSeconds(3_601)).chat(-100L)
        f.interests.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        f.batcher.flushAllOnStartup()
        f.announcements.shouldBeEmpty()
        f.pending.all().shouldBeEmpty()
        f.requests.resting(-100L) shouldHaveSize 1
    }

    "an announcement exactly an hour old is still sent" {
        val f = BatchFixture("keepatcutoff", at = T0.plusSeconds(3_600)).chat(-100L)
        f.interests.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        f.batcher.flushAllOnStartup()
        f.announcements shouldHaveSize 1
        f.pending.all().shouldBeEmpty()
    }

    "a restart inside the window still tells every chat" {
        val f = BatchFixture("restart").chat(-100L).chat(-200L)
        f.interests.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        f.interests.state(2L, "ann", Verb.BUY, "10", "EUR", "RUB")
        // A batcher that has never seen either statement, exactly as one would be after a restart.
        f.build().flushAllOnStartup()
        f.announcements shouldHaveSize 4 // two people, two chats each
        f.announcements.map { it.chatId }.toSet() shouldBe setOf(-100L, -200L)
        // One message per chat PER PERSON: neither person's message is attributed to the other.
        for (chatId in listOf(-100L, -200L)) {
            f.announcements.count { it.chatId == chatId && it.text.contains("on @bob's behalf") } shouldBe 1
            f.announcements.count { it.chatId == chatId && it.text.contains("on @ann's behalf") } shouldBe 1
        }
        f.pending.all().shouldBeEmpty()
    }

    "two flushes racing on one person still send one message" {
        // A send that takes its time is what makes the overlap real rather than theoretical:
        // both flushes are inside `flush` before either has finished with the row.
        val f = BatchFixture("racing", sinkDelayMs = 200).chat(-100L)
        f.interests.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        coroutineScope {
            launch(Dispatchers.Default) { f.batcher.flush(1L) }
            launch(Dispatchers.Default) { f.batcher.flush(1L) }
        }
        f.sinkCalls.get() shouldBe 1
        f.announcements shouldHaveSize 1
        f.pending.all().shouldBeEmpty()
    }

    "a send that fails leaves the chat still owed its announcement" {
        val f = BatchFixture("sinkfails").chat(-100L)
        f.sinkFails = true
        f.interests.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        shouldThrow<IllegalStateException> { f.batcher.flush(1L) }
        f.announcements.shouldBeEmpty()
        f.pending.all() shouldHaveSize 1
        // Owed, so the next attempt re-renders it and it goes out exactly once.
        f.sinkFails = false
        f.batcher.flush(1L)
        f.announcements shouldHaveSize 1
        f.pending.all().shouldBeEmpty()
    }

    "one failing send does not stop the batcher announcing ever again" {
        val f = BatchFixture("sinksurvives", window = Duration.ofMillis(200)).chat(-100L)
        f.sinkFails = true
        f.interests.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        f.batcher.enqueueAnnouncement(1L)
        f.awaitUntil("the first window firing and failing") { f.sinkCalls.get() >= 1 }
        f.announcements.shouldBeEmpty()
        // A later window must still fire: an exception escaping the first one would have
        // cancelled the scope every window is launched into.
        f.sinkFails = false
        f.interests.state(2L, "ann", Verb.BUY, "10", "EUR", "RUB")
        f.batcher.enqueueAnnouncement(2L)
        f.awaitAnnouncements(System.nanoTime())
        f.announcements.map { it.chatId }.toSet() shouldBe setOf(-100L)
    }

    "the ping window is keyed by the recipient, not by whoever caused it" {
        // Two different people turn up for the same recipient inside one window, each
        // against a different interest of his, and he hears about it once.
        val f = BatchFixture("pingwindow", window = Duration.ofMillis(400))
        f.interests.state(1L, "bob", Verb.SELL, "1000", "EUR", "RUB")
        f.interests.state(1L, "bob", Verb.SELL, "5000", "GBP", "USD")
        val a = f.interests.state(2L, "ann", Verb.BUY, "1000", "EUR", "RUB") as InterestResult.Stated
        f.batcher.enqueueAppeared(a.appeared)
        delay(150)
        val c = f.interests.state(3L, "cat", Verb.BUY, "5000", "GBP", "USD") as InterestResult.Stated
        f.batcher.enqueueAppeared(c.appeared)
        f.awaitUntil("the ping window") { f.pings.isNotEmpty() }
        // A second window would have fired 150ms after the first; give it every chance to.
        delay(500)
        f.pings shouldHaveSize 1
        f.pings.single().userId shouldBe 1L
        val text = f.pings.single().text
        text shouldContain "sell 1,000 EUR"
        text shouldContain "sell 5,000 GBP"
        // One counterparty under each of his interests, each named.
        Regex("buy 1,000 EUR|buy 5,000 GBP").findAll(text).count() shouldBe 2
        text shouldContain "@ann"
        text shouldContain "@cat"
        // Each done pairs a counterparty with the interest of bob's it was found against.
        // Two interests is what makes this test able to see the difference: pairing both
        // with bob's FIRST row renders identically and dead-buttons the second via
        // LifecycleService.done's pairing check.
        val bobsEur = f.requests.resting(NO_CHAT_ID).single { it.userId == 1L && it.statedCurrency == "EUR" }
        val bobsGbp = f.requests.resting(NO_CHAT_ID).single { it.userId == 1L && it.statedCurrency == "GBP" }
        f.pings.single().buttons.map { it.data } shouldBe listOf(
            Cb.done(bobsEur.refToken, a.interest.refToken),
            Cb.done(bobsGbp.refToken, c.interest.refToken),
        )
    }
})
