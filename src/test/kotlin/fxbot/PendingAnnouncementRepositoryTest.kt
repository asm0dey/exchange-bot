package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val EURRUB = CurrencyPair("EUR", "RUB")
private val T0 = Instant.parse("2026-09-06T12:00:00Z")

private fun pendings(name: String, at: Instant = T0): Pair<PendingAnnouncementRepository, RequestRepository> {
    val ds = memDataSource(name)
    migrate(ds)
    val crypto = testCrypto()
    val clock = Clock.fixed(at, ZoneOffset.UTC)
    return PendingAnnouncementRepository(ds, crypto, clock) to RequestRepository(ds, crypto, clock)
}

class PendingAnnouncementRepositoryTest : StringSpec({
    "what is owed an announcement is remembered, and can be tied back to its chat" {
        val (p, _) = pendings("pendadd")
        p.add(-100L, "i1", 1L)
        val row = p.all().single()
        row.interestToken shouldBe "i1"
        p.isFor(row, -100L) shouldBe true
        p.isFor(row, -200L) shouldBe false
    }
    "the same chat and interest is one row however often it is added" {
        val (p, _) = pendings("penddupe")
        p.add(-100L, "i1", 1L)
        p.add(-100L, "i1", 1L)
        p.all() shouldHaveSize 1
    }
    "a person's own pending announcements can be picked out" {
        val (p, _) = pendings("pendmine")
        p.add(-100L, "i1", 1L)
        p.add(-100L, "i2", 2L)
        p.allFor(1L).single().interestToken shouldBe "i1"
    }
    "a flushed announcement is removed" {
        val (p, _) = pendings("pendremove")
        p.add(-100L, "i1", 1L)
        val row = p.all().single()
        p.remove(row.chatRef, row.interestToken)
        p.all() shouldHaveSize 0
    }
    "an announcement older than an hour is dropped" {
        val (p, _) = pendings("pendstale")
        p.add(-100L, "i1", 1L)
        p.dropOlderThan(T0.minusSeconds(1)) shouldBe 0
        p.dropOlderThan(T0.plusSeconds(3_601)) shouldBe 1
        p.all() shouldHaveSize 0
    }
    "an announcement whose showings have all closed is dropped" {
        val (p, requests) = pendings("penddead")
        requests.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("10"), EURRUB, 7, "i1")
        p.add(-100L, "i1", 1L)
        p.add(-200L, "i-never-existed", 1L)
        p.dropClosed() shouldBe 1
        p.all().single().interestToken shouldBe "i1"
        requests.closeInterest("i1", RequestState.CANCELLED)
        p.dropClosed() shouldBe 1
        p.all() shouldHaveSize 0
    }
    "a supergroup upgrade takes the pending announcements with it" {
        val (p, _) = pendings("pendmigrate")
        p.add(-100L, "i1", 1L)
        p.add(-200L, "i2", 2L)
        p.rewriteChatRef(-100L, -1001L) shouldBe 1
        val moved = p.all().single { it.interestToken == "i1" }
        p.isFor(moved, -1001L) shouldBe true
        p.isFor(moved, -100L) shouldBe false
        moved.createdAt shouldBe T0
        // The person the row belongs to is unchanged by a chat rewrite.
        p.allFor(1L) shouldHaveSize 1
        // The other chat's row was never touched.
        p.isFor(p.all().single { it.interestToken == "i2" }, -200L) shouldBe true
    }
    "forgetting drops the person's pending announcements" {
        val (p, _) = pendings("pendforget")
        p.add(-100L, "i1", 1L)
        p.add(-100L, "i2", 2L)
        p.deleteFor(1L)
        p.all().single().interestToken shouldBe "i2"
    }
})
