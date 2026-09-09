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
    val giveUps = NameGiveUpRepository(ds, crypto, clock)
    val pending = PendingAnnouncementRepository(ds, crypto, clock)
    val svc = ForgetService(requests, log, people, giveUps, pending, clock)
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
    "a personal forget erases the person's own settings, consents and pending announcements" {
        val f = Fixture("personal")
        f.people.save(PersonSettings(1L, 40))
        f.giveUps.record("tokA", "tokB", 1L, Stance.OFFERED)
        f.pending.add(-100L, "i1", 1L)
        // Another person's rows, in the same three tables.
        f.people.save(PersonSettings(2L, 40))
        f.giveUps.record("tokC", "tokD", 2L, Stance.OFFERED)
        f.pending.add(-100L, "i2", 2L)

        f.svc.plan(1L, NO_CHAT_ID, personal = true)

        f.people.get(1L).tolerancePct shouldBe DEFAULT_PERSON_TOLERANCE
        f.giveUps.stanceOf("tokA", "tokB") shouldBe null
        f.pending.allFor(1L) shouldHaveSize 0
        f.people.get(2L).tolerancePct shouldBe 40
        f.giveUps.stanceOf("tokC", "tokD") shouldBe Stance.OFFERED
        f.pending.allFor(2L) shouldHaveSize 1
    }
    "a per-chat forget leaves the three personal tables alone" {
        val f = Fixture("impersonal")
        f.people.save(PersonSettings(1L, 40))
        f.giveUps.record("tokA", "tokB", 1L, Stance.OFFERED)
        f.pending.add(-100L, "i1", 1L)

        f.svc.plan(1L, -100L, personal = false)

        f.people.get(1L).tolerancePct shouldBe 40
        f.giveUps.stanceOf("tokA", "tokB") shouldBe Stance.OFFERED
        f.pending.allFor(1L) shouldHaveSize 1
    }
    "the messages cleaned up can be scoped to a different chat from the requests erased" {
        val f = Fixture("messagescope")
        // The private form: requests are erased on the no-names side, but the messages to
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
