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
    val svc = ForgetService(requests, log, clock)
}

class ForgetServiceTest : StringSpec({
    "removes the person's requests in this chat only" {
        val f = Fixture("scope")
        f.requests.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        f.requests.create(-200L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        f.svc.plan(1L, -100L).deletedRequests shouldBe 1
        f.requests.resting(-200L) shouldHaveSize 1
    }
    "a global forget reaches every chat" {
        val f = Fixture("global")
        f.requests.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        f.requests.create(-200L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        f.svc.plan(1L, null).deletedRequests shouldBe 2
    }
    "a message naming only them is deleted; one naming others is redacted" {
        val f = Fixture("redact")
        f.log.record(-100L, 10L, listOf("tokA"), listOf(1L))
        f.log.record(-100L, 11L, listOf("tokA", "tokB"), listOf(1L, 2L))
        val plan = f.svc.plan(1L, -100L)
        plan.toDelete.map { it.messageId } shouldBe listOf(10L)
        plan.toRedact.map { it.messageId } shouldBe listOf(11L)
    }
    "the tracking rows for that person are gone afterwards" {
        val f = Fixture("cleared")
        f.log.record(-100L, 10L, listOf("tokA"), listOf(1L))
        f.svc.plan(1L, -100L)
        f.log.messagesForUser(1L, -100L) shouldHaveSize 0
    }
})
