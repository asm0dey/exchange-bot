package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val EURRUB = CurrencyPair("EUR", "RUB")
private val T0 = Instant.parse("2026-08-30T12:00:00Z")

private class Fixture(name: String) {
    val ds = memDataSource(name).also { migrate(it) }
    val crypto = testCrypto()
    val clock: Clock = Clock.fixed(T0, ZoneOffset.UTC)
    val requests = RequestRepository(ds, crypto, clock)
    val log = MessageLogRepository(ds, crypto, clock)
    val people = PersonSettingsRepository(ds, crypto, clock)
    val refusals = DoneRefusalRepository(ds, clock)
    val pending = PendingAnnouncementRepository(ds, crypto, clock)
    val svc = ForgetService(requests, log, people, refusals, pending, clock)
}

class ForgetServiceTest : StringSpec({
    "removes the person's requests in this chat only" {
        val f = Fixture("scope")
        f.requests.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        f.requests.create(-200L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        f.svc.plan(1L, -100L, personal = false).deletedRequests shouldBe 1
        f.requests.resting(-200L) shouldHaveSize 1
    }
    "a global forget reaches every chat" {
        val f = Fixture("global")
        f.requests.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        f.requests.create(-200L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        f.svc.plan(1L, null, personal = true).deletedRequests shouldBe 2
    }
    "a message naming only them is deleted; one naming others is redacted" {
        val f = Fixture("redact")
        f.log.record(-100L, 10L, listOf("tokA"), listOf(1L))
        f.log.record(-100L, 11L, listOf("tokA", "tokB"), listOf(1L, 2L))
        val plan = f.svc.plan(1L, -100L, personal = false)
        plan.toDelete.map { it.messageId } shouldBe listOf(10L)
        plan.toRedact.map { it.messageId } shouldBe listOf(11L)
    }
    "the tracking rows for that person are gone afterwards" {
        val f = Fixture("cleared")
        f.log.record(-100L, 10L, listOf("tokA"), listOf(1L))
        f.svc.plan(1L, -100L, personal = false)
        f.log.messagesForUser(1L, -100L) shouldHaveSize 0
    }
    "a personal forget erases the person's own settings and pending announcements" {
        val f = Fixture("personal")
        f.people.save(PersonSettings(1L, 40))
        f.pending.add(-100L, "i1", 1L)
        // Another person's rows, in the same two tables.
        f.people.save(PersonSettings(2L, 40))
        f.pending.add(-100L, "i2", 2L)

        f.svc.plan(1L, NO_CHAT_ID, personal = true)

        f.people.get(1L).tolerancePct shouldBe DEFAULT_PERSON_TOLERANCE
        f.pending.allFor(1L) shouldHaveSize 0
        f.people.get(2L).tolerancePct shouldBe 40
        f.pending.allFor(2L) shouldHaveSize 1
    }
    "a per-chat forget leaves the personal tables alone, but still erases the refusals" {
        val f = Fixture("impersonal")
        f.people.save(PersonSettings(1L, 40))
        f.pending.add(-100L, "i1", 1L)
        val mine = f.requests.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        val theirs = f.requests.create(-100L, 2L, "b", Side.BID, "EUR", BigDecimal("1"), EURRUB, 7)
        f.refusals.record(theirs.refToken, mine.refToken)

        f.svc.plan(1L, -100L, personal = false)

        f.people.get(1L).tolerancePct shouldBe 40
        f.pending.allFor(1L) shouldHaveSize 1
        // Erased whatever the shape of the forgetting: the request the row names is gone.
        f.refusals.count(theirs.refToken, mine.refToken) shouldBe 0
    }
    "the messages cleaned up can be scoped to a different chat from the requests erased" {
        val f = Fixture("messagescope")
        // The private form: requests are erased on the bot-side, but the messages to
        // clean up are the ones in the person's real private chat — nothing is ever
        // recorded under the sentinel.
        f.requests.create(NO_CHAT_ID, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7, "i1")
        f.log.record(555L, 10L, listOf("tokA", "tokB"), listOf(1L, 2L))
        f.log.record(-100L, 11L, listOf("tokA"), listOf(1L))

        val plan = f.svc.plan(1L, NO_CHAT_ID, personal = true, messageChatId = 555L)

        plan.deletedRequests shouldBe 1
        plan.toRedact.map { it.messageId } shouldBe listOf(10L)
        plan.toDelete shouldHaveSize 0
        // The group's record was out of scope and is still there.
        f.log.messagesForUser(1L, -100L) shouldHaveSize 1
    }
})
